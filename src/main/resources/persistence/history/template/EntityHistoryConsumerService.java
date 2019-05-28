package  ${historicalEntity.getServicePackageName()};

import java.util.Map;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;
import org.coldis.library.serialization.ObjectMapperHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import ${historicalEntity.getEntityQualifiedTypeName()};
import ${historicalEntity.getRepositoryQualifiedTypeName()};

/**
 * JPA entity history consumer service for {@link ${historicalEntity.getOriginalEntityQualifiedTypeName()}}.
 */
@Controller
public class ${historicalEntity.getConsumerServiceTypeName()} {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(${historicalEntity.getConsumerServiceTypeName()}.class);
	
	/**
	 * Historical entity queue.
	 */
	private static final String HISTORICAL_ENTITY_QUEUE = "${historicalEntity.getQueueName()}";
	
	/**
	 * Object mapper.
	 */
	@Autowired
	private ObjectMapper objectMapper;

	/**
	 * Entity history repository.
	 */
	@Autowired
	private ${historicalEntity.getRepositoryTypeName()} repository;

	/**
	 * Actually handles the entity state update and saves in the historical data.
	 * @param state	Current entity state.
	 */
	@Transactional
	@JmsListener(destination = ${historicalEntity.getConsumerServiceTypeName()}.HISTORICAL_ENTITY_QUEUE)
	public void handleUpdate(final String state) {
		${historicalEntity.getConsumerServiceTypeName()}.LOGGER.debug("Processing '${historicalEntity.getEntityQualifiedTypeName()}' history update."); 
		// Tries to process the entity history update.
		try {
			// Saves the new entity history state.
			this.repository.save(new ${historicalEntity.getEntityTypeName()}(ObjectMapperHelper.deserialize(objectMapper, state, new TypeReference<Map<String, Object>>() {
			}, false)));
			${historicalEntity.getConsumerServiceTypeName()}.LOGGER.debug("'${historicalEntity.getEntityQualifiedTypeName()}' history update processed."); 
		}
		// If the entity state cannot be saved as historical data.
		catch (final Exception exception) {
			// Throws an entity history update error.
			throw new IntegrationException(new SimpleMessage("entity.history.update.failed"), exception);
		}
	}

}