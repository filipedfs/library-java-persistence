package org.coldis.library.test.persistence.batch;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Random;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.persistence.batch.BatchExecutor;
import org.coldis.library.persistence.batch.BatchService;
import org.coldis.library.persistence.keyvalue.KeyValueService;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.persistence.TestApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jms.annotation.EnableJms;

/**
 * Batch record test.
 */
@EnableJms
@SpringBootTest(
		webEnvironment = WebEnvironment.RANDOM_PORT,
		classes = TestApplication.class
)
public class BatchServiceTest {

	/**
	 * Regular clock.
	 */
	public static final Clock REGULAR_CLOCK = DateTimeHelper.getClock();

	/**
	 * Random.
	 */
	public static final Random RANDOM = new Random();

	/**
	 * Key/value service.
	 */
	@Autowired
	private KeyValueService keyValueService;

	/**
	 * Batch service.
	 */
	@Autowired
	private BatchService batchService;

	/**
	 * Cleans after each test.
	 *
	 * @throws Exception If the test fails.
	 */
	@BeforeEach
	public void cleanBeforeEachTest() throws Exception {
		TestHelper.waitUntilValid(() -> {
			try {
				this.batchService.cleanAll();
				return this.keyValueService.findByKeyStart("batch-record");
			}
			catch (final BusinessException e) {
				return List.of();
			}
		}, List::isEmpty, TestHelper.VERY_LONG_WAIT * 3, TestHelper.SHORT_WAIT);
		BatchTestService.processedAlways = 0L;
		BatchTestService.processedLatestPartialBatch = 0L;
		BatchTestService.processedLatestCompleteBatch = 0L;
	}

	/**
	 * Cleans after each test.
	 *
	 * @throws BusinessException
	 */
	@AfterEach
	public void cleanAfterEachTest() throws BusinessException {
		// Sets back to the regular clock.
		DateTimeHelper.setClock(BatchServiceTest.REGULAR_CLOCK);
	}

	/**
	 * Tests a batch execution.
	 *
	 * @param  testBatchExecutor Executor.
	 * @throws Exception         If the test fails.
	 */
	@SuppressWarnings("unchecked")
	private void testBatch(
			final BatchExecutor<BatchObject> testBatchExecutor,
			final Long processedNow,
			final Long processedTotal) throws BusinessException, Exception {

		final String batchKey = this.batchService.getKey(testBatchExecutor.getKeySuffix());

		// Starts the batch and makes sure it has started.
		BatchTestService.processDelay = 1L;
		this.batchService.checkAll();
		this.batchService.executeCompleteBatch(testBatchExecutor, true);
		this.batchService.checkAll();

		BatchExecutor<BatchObject> batchRecord = (BatchExecutor<BatchObject>) this.keyValueService.findById(batchKey, false).getValue();
		Assertions.assertTrue(batchRecord.getLastProcessedCount() > 0);
		Assertions.assertNotNull(batchRecord.getLastStartedAt());
		Assertions.assertNotNull(batchRecord.getLastProcessed());
		Assertions.assertNull(batchRecord.getLastFinishedAt());

		// Waits until batch is finished.
		TestHelper.waitUntilValid(() -> {
			try {
				return (BatchExecutor<BatchObject>) this.keyValueService.findById(batchKey, false).getValue();
			}
			catch (final BusinessException e) {
				return null;
			}
		}, record -> record.getLastFinishedAt() != null, TestHelper.VERY_LONG_WAIT, TestHelper.SHORT_WAIT);
		batchRecord = (BatchExecutor<BatchObject>) this.keyValueService.findById(batchKey, false).getValue();
		Assertions.assertEquals(processedNow, batchRecord.getLastProcessedCount());
		Assertions.assertEquals(processedTotal, BatchTestService.processedAlways);
		Assertions.assertNotNull(batchRecord.getLastStartedAt());
		Assertions.assertNotNull(batchRecord.getLastProcessed());
		Assertions.assertNotNull(batchRecord.getLastFinishedAt());
		Assertions.assertTrue(batchRecord.isFinished());

		// Tries executing the batch again, and nothing should change.
		this.batchService.checkAll();
		this.batchService.executeCompleteBatch(testBatchExecutor, false);
		this.batchService.checkAll();
		batchRecord = (BatchExecutor<BatchObject>) this.keyValueService.findById(batchKey, false).getValue();
		Assertions.assertEquals(processedNow, batchRecord.getLastProcessedCount());
		Assertions.assertEquals(processedTotal, BatchTestService.processedAlways);
	}

	/**
	 * Tests a batch.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testBatchInTime() throws Exception {

		// Makes sure the batch is not started.
		final BatchExecutor<BatchObject> testBatchExecutor = new BatchExecutor<>(BatchObject.class, "test", 10L, null, Duration.ofSeconds(30),
				"batchTestService", null, null, null);
		final String batchKey = this.batchService.getKey(testBatchExecutor.getKeySuffix());

		// Record should not exist.
		try {
			this.keyValueService.findById(batchKey, false).getValue();
			Assertions.fail("Record should not exist.");
		}
		catch (final Exception exception) {
		}

		// Tests the batch twice.
		this.testBatch(testBatchExecutor, 100L, 100L);
		DateTimeHelper.setClock(Clock.offset(DateTimeHelper.getClock(), Duration.ofHours(1)));
		this.testBatch(testBatchExecutor, 100L, 200L);

		// Advances the clock and make sure the record is deleted.
		DateTimeHelper.setClock(Clock.offset(DateTimeHelper.getClock(), Duration.ofHours(6)));
		this.batchService.checkAll();
		TestHelper.waitUntilValid(() -> {
			try {
				return this.keyValueService.findByKeyStart("batch-record");
			}
			catch (final BusinessException e) {
				return List.of();
			}
		}, List::isEmpty, TestHelper.VERY_LONG_WAIT * 3, TestHelper.SHORT_WAIT);
		try {
			this.keyValueService.findById(batchKey, false);
			Assertions.fail("Batch should no longer exist.");
		}
		catch (final Exception exception) {
		}

	}

	/**
	 * Tests a batch.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@SuppressWarnings("unchecked")
	public void testBatchNotInTime() throws Exception {

		// Makes sure the batch is not started.
		final BatchExecutor<BatchObject> testBatchExecutor = new BatchExecutor<>(BatchObject.class, "test", 10L, null, Duration.ofSeconds(30),
				"batchTestService", null, null, null);
		final String batchKey = this.batchService.getKey(testBatchExecutor.getKeySuffix());

		// Record should not exist.
		try {
			this.keyValueService.findById(batchKey, false).getValue();
			Assertions.fail("Record should not exist.");
		}
		catch (final Exception exception) {
		}

		// Runs the clock forward and executes the batch again (now with a bigger delay
		// so it should not finish in time).
		BatchTestService.processDelay = 1000L;
		this.batchService.checkAll();
		this.batchService.executeCompleteBatch(testBatchExecutor, true);
		this.batchService.checkAll();

		BatchExecutor<BatchObject> batchRecord = (BatchExecutor<BatchObject>) this.keyValueService.findById(batchKey, false).getValue();

		// Waits for a while (this batch should not reach the end).
		TestHelper.waitUntilValid(() -> {
			try {
				return (BatchExecutor<BatchObject>) this.keyValueService.findById(batchKey, false).getValue();
			}
			catch (final BusinessException e) {
				return null;
			}
		}, record -> record.getLastFinishedAt() != null, TestHelper.VERY_LONG_WAIT * 2, TestHelper.SHORT_WAIT);
		batchRecord = (BatchExecutor<BatchObject>) this.keyValueService.findById(batchKey, false).getValue();
		Assertions.assertTrue(BatchTestService.processedAlways > 0);
		Assertions.assertTrue(BatchTestService.processedAlways < 100);
		Assertions.assertTrue(batchRecord.getLastProcessedCount() > 0);
		Assertions.assertTrue(batchRecord.getLastProcessedCount() < 100);
		Assertions.assertNull(batchRecord.getLastFinishedAt());
		Assertions.assertFalse(batchRecord.isFinished());

		// Advances the clock and make sure the record is deleted.
		DateTimeHelper.setClock(Clock.offset(DateTimeHelper.getClock(), Duration.ofHours(6)));
		this.batchService.checkAll();
		TestHelper.waitUntilValid(() -> {
			try {
				return this.keyValueService.findByKeyStart("batch-record");
			}
			catch (final BusinessException e) {
				return List.of();
			}
		}, List::isEmpty, TestHelper.VERY_LONG_WAIT * 3, TestHelper.SHORT_WAIT);
		try {
			this.keyValueService.findById(batchKey, false);
			Assertions.fail("Batch should no longer exist.");
		}
		catch (final Exception exception) {
		}

	}

}
