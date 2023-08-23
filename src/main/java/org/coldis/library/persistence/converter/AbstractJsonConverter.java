package org.coldis.library.persistence.converter;

import javax.persistence.AttributeConverter;

import org.coldis.library.model.view.ModelView;
import org.coldis.library.persistence.configuration.JpaAutoConfiguration;
import org.coldis.library.serialization.ObjectMapperHelper;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Abstract JPA converter to JSON object (String).
 *
 * @param <ObjectType> Any type.
 */
public abstract class AbstractJsonConverter<ObjectType> implements AttributeConverter<ObjectType, String> {

	/**
	 * Serialization view to be used.
	 */
	private static final Class<?> SERIALIZATION_VIEW = ModelView.Persistent.class;

	/**
	 * Returns the object mapper.
	 *
	 * @return The object mapper.
	 */
	protected ObjectMapper getObjectMapper() {
		return (JpaAutoConfiguration.OBJECT_MAPPER == null ? ObjectMapperHelper.createMapper() : JpaAutoConfiguration.OBJECT_MAPPER);
	}

	/**
	 * @see javax.persistence.AttributeConverter#convertToDatabaseColumn(java.lang.Object)
	 */
	@Override
	public String convertToDatabaseColumn(
			final ObjectType originalObject) {
		// Returns the JSON object.
		return ObjectMapperHelper.serialize(this.getObjectMapper(), originalObject, AbstractJsonConverter.SERIALIZATION_VIEW, false);
	}

	/**
	 * Converts the JSON object to the entity type.
	 *
	 * @param  jsonMapper Object mapper to be used.
	 * @param  jsonObject JSON object.
	 * @return            Converted JSON object.
	 */
	protected abstract ObjectType convertToEntityAttribute(
			final ObjectMapper jsonMapper,
			final String jsonObject);

	/**
	 * @see javax.persistence.AttributeConverter#convertToEntityAttribute(java.lang.Object)
	 */
	@Override
	public ObjectType convertToEntityAttribute(
			final String jsonObject) {
		return jsonObject == null ? null : this.convertToEntityAttribute(this.getObjectMapper(), jsonObject);
	}

}
