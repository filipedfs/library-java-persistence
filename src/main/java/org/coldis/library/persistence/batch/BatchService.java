package org.coldis.library.persistence.batch;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

import org.apache.commons.lang3.StringUtils;
import org.coldis.library.exception.BusinessException;
import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.model.Typable;
import org.coldis.library.persistence.keyvalue.KeyValue;
import org.coldis.library.persistence.keyvalue.KeyValueService;
import org.coldis.library.service.slack.SlackIntegration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.PropertyPlaceholderHelper;

/**
 * Batch helper.
 */
@Component
public class BatchService {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(BatchService.class);

	/**
	 * Batch key prefix.
	 */
	public static final String BATCH_KEY_PREFIX = "batch-record-";

	/**
	 * Batch lock key prefix.
	 */
	public static final String BATCH_LOCK_KEY_PREFIX = BatchService.BATCH_KEY_PREFIX + "lock-";

	/**
	 * Batch record execute queue.
	 */
	private static final String BATCH_RECORD_EXECUTE_QUEUE = "BatchRecordExecuteQueue";

	/**
	 * Placeholder resolver.
	 */
	private static final PropertyPlaceholderHelper PLACEHOLDER_HELPER = new PropertyPlaceholderHelper("${", "}");

	/**
	 * JMS template.
	 */
	@Autowired(required = false)
	private JmsTemplate jmsTemplate;

	/**
	 * Key batchRecordValue service.
	 */
	@Autowired(required = false)
	private KeyValueService keyValueService;

	/**
	 * Slack integration.
	 */
	@Autowired
	private SlackIntegration slackIntegration;

	/**
	 * Gets the batch key.
	 *
	 * @param  keySuffix Batch key suffix.
	 * @return           Batch key.
	 */
	public String getKey(
			final String keySuffix) {
		return BatchService.BATCH_KEY_PREFIX + keySuffix;
	}

	/**
	 * Gets the batch key.
	 *
	 * @param  keySuffix Batch key suffix.
	 * @return           Batch key.
	 */
	public String getLockKey(
			final String keySuffix) {
		return BatchService.BATCH_LOCK_KEY_PREFIX + keySuffix;
	}

	/**
	 * Get the last id processed in the batch.
	 *
	 * @param  keySuffix  The batch key suffix.
	 * @param  expiration Maximum interval to finish the batch.
	 * @return            The last id processed.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public String getLastProcessedId(
			final String keySuffix,
			final LocalDateTime expiration) {

		// Gets the batch record (and initiates it if necessary).
		final String key = this.getKey(keySuffix);
		KeyValue<Typable> batchRecord = this.keyValueService.lock(key).get();
		if (batchRecord.getValue() == null) {
			batchRecord.setValue(new BatchRecord());
		}

		// Gets the last processed id.
		final BatchRecord batchRecordValue = (BatchRecord) batchRecord.getValue();
		String lastProcessedId = batchRecordValue.getLastProcessedId();

		// Clears the last processed id, if data has expired.
		if ((batchRecordValue.getLastStartedAt() == null) || (expiration == null) || batchRecordValue.getLastStartedAt().isBefore(expiration)) {
			batchRecordValue.reset();
			lastProcessedId = null;
		}

		// Returns the last processed id.
		batchRecord = this.keyValueService.getRepository().save(batchRecord);
		return lastProcessedId;
	}

	/**
	 * Logs the action.
	 *
	 * @param  executor          Executor.
	 * @param  action            Action.
	 * @throws BusinessException Exception.
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	private void log(
			final BatchExecutor executor,
			final BatchAction action) throws BusinessException {
		try {
			// Gets the template and Slack channel.
			final String template = executor.getMessagesTemplates().get(action);
			final String slackChannel = executor.getSlackChannels().get(action);
			// If the template is given.
			if (StringUtils.isNotBlank(template)) {
				// Gets the message properties.
				final String key = this.getKey(executor.getKeySuffix());
				final String id = executor.getLastProcessedId();
				final KeyValue<Typable> batchRecord = this.keyValueService.findById(key, false);
				final BatchRecord batchRecordValue = (BatchRecord) batchRecord.getValue();
				final Long duration = (batchRecordValue.getLastStartedAt() == null ? 0
						: batchRecordValue.getLastStartedAt().until(DateTimeHelper.getCurrentLocalDateTime(), ChronoUnit.MINUTES));
				final Properties messageProperties = new Properties();
				messageProperties.put("key", key);
				messageProperties.put("id", id);
				messageProperties.put("duration", duration);
				// Gets the message from the template.
				final String message = BatchService.PLACEHOLDER_HELPER.replacePlaceholders(template, messageProperties);
				// If there is a message.
				if (StringUtils.isNoneBlank(message)) {
					BatchService.LOGGER.info(message);
					// If there is a channel to use, sends the message.
					if (StringUtils.isNotBlank(slackChannel)) {
						this.slackIntegration.send(slackChannel, message);
					}
				}
			}
		}
		// Ignores errors.
		catch (final Throwable exception) {
			BatchService.LOGGER.error("Batch action could not be logged: " + exception.getLocalizedMessage());
			BatchService.LOGGER.debug("Batch action could not be logged.", exception);
		}

	}

	/**
	 * Processes a partial batch.
	 *
	 * @param  executor          Executor.
	 * @return                   The last processed id.
	 * @throws BusinessException If the batch could not be processed.
	 */
	@Transactional(
			propagation = Propagation.REQUIRES_NEW,
			timeout = 173
	)
	protected String executePartialBatch(
			final BatchExecutor executor) throws BusinessException {

		// Gets the batch record.
		final KeyValue<Typable> batchRecord = this.keyValueService.findById(this.getKey(executor.getKeySuffix()), true);
		final BatchRecord batchRecordValue = (BatchRecord) batchRecord.getValue();
		String actualLastProcessedId = executor.getLastProcessedId();

		// Updates the last processed start.
		if ((executor.getLastProcessedId() == null) || (batchRecordValue.getLastStartedAt() == null)) {
			batchRecordValue.setLastStartedAt(DateTimeHelper.getCurrentLocalDateTime());
		}

		// If the batch has not expired.
		if (!batchRecordValue.getLastStartedAt().isBefore(executor.getExpiration())) {

			// For each item in the next batch.
			this.log(executor, BatchAction.GET);
			final List<String> nextBatchToProcess = executor.get();
			for (final String nextId : nextBatchToProcess) {
				executor.execute(nextId);
				this.log(executor, BatchAction.EXECUTE);
				actualLastProcessedId = nextId;
				batchRecordValue.setLastProcessedCount(batchRecordValue.getLastProcessedCount() + 1);
			}

		}

		// Updates the last processed id.
		batchRecordValue.setLastProcessedId(actualLastProcessedId);
		this.keyValueService.getRepository().save(batchRecord);

		// Returns the last processed. id.
		return actualLastProcessedId;
	}

	/**
	 * Processes a complete batch.
	 *
	 * @param  executor          Executor.
	 * @throws BusinessException If the batch fails.
	 */
	@JmsListener(
			destination = BatchService.BATCH_RECORD_EXECUTE_QUEUE,
			concurrency = "1-7"
	)
	@Transactional(
			propagation = Propagation.REQUIRED,
			noRollbackFor = Throwable.class,
			timeout = 1237
	)
	public void executeCompleteBatch(
			final BatchExecutor executor) throws BusinessException {
		// Synchronizes the batch (preventing to happen in parallel).
		final String lockKey = this.getLockKey(executor.getKeySuffix());
		this.keyValueService.lock(lockKey);
		try {
			// Gets the next id to be processed.
			final String initialProcessedId = this.getLastProcessedId(executor.getKeySuffix(), executor.getExpiration());
			String previousLastProcessedId = initialProcessedId;
			String currentLastProcessedId = initialProcessedId;
			boolean justStarted = true;

			// Starts or resumes the batch.
			if (initialProcessedId == null) {
				executor.start();
				this.log(executor, BatchAction.START);
			}
			else {
				executor.resume();
				this.log(executor, BatchAction.RESUME);
			}

			// Runs the batch until the next id does not change.
			while (justStarted || !Objects.equals(previousLastProcessedId, currentLastProcessedId)) {
				justStarted = false;
				executor.setLastProcessedId(currentLastProcessedId);
				final String nextLastProcessedId = this.executePartialBatch(executor);
				previousLastProcessedId = currentLastProcessedId;
				currentLastProcessedId = nextLastProcessedId;
			}

			// Finishes the batch.
			if (!Objects.equals(initialProcessedId, currentLastProcessedId)) {
				executor.finish();
				this.log(executor, BatchAction.FINISH);
				final KeyValue<Typable> batchRecord = this.keyValueService.findById(this.getKey(executor.getKeySuffix()), true);
				final BatchRecord batchRecordValue = (BatchRecord) batchRecord.getValue();
				batchRecordValue.setLastFinishedAt(DateTimeHelper.getCurrentLocalDateTime());
				this.keyValueService.getRepository().save(batchRecord);
			}

		}
		// If there is an error in the batch, retry.
		catch (final Throwable throwable) {
			BatchService.LOGGER.error("Error processing batch '" + this.getKey(executor.getKeySuffix()) + "': " + throwable.getLocalizedMessage());
			BatchService.LOGGER.debug("Error processing batch '" + this.getKey(executor.getKeySuffix()) + "'.", throwable);
			this.processExecuteCompleteBatchAsync(executor);
			throw throwable;
		}
		// Releases the lock.
		finally {
			this.keyValueService.delete(lockKey);
		}

	}

	/**
	 * Processes a complete batch.
	 *
	 * @param  executor          Executor.
	 * @throws BusinessException If the batch fails.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void processExecuteCompleteBatchAsync(
			final BatchExecutor executor) throws BusinessException {
		this.jmsTemplate.convertAndSend(BatchService.BATCH_RECORD_EXECUTE_QUEUE, executor);
	}

}
