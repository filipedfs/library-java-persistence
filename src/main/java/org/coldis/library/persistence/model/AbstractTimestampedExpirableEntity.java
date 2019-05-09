package org.coldis.library.persistence.model;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.EntityListeners;
import javax.persistence.MappedSuperclass;
import javax.persistence.Transient;

import org.coldis.library.dto.DtoAttribute;
import org.coldis.library.model.AbstractTimestampedExpirableObject;
import org.coldis.library.model.ModelView;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonView;

/**
 * Abstract JPA entity that might expire and is time-stamped.
 */
@MappedSuperclass
@EntityListeners(value = EntityTimestampListener.class)
public abstract class AbstractTimestampedExpirableEntity extends AbstractTimestampedExpirableObject {

	/**
	 * Serial.
	 */
	private static final long serialVersionUID = 516365864675481043L;

	/**
	 * @see org.coldis.library.model.AbstractTimestampedObject#getCreatedAt()
	 */
	@Override
	@DtoAttribute(readOnly = true, usedInComparison = false)
	@Column(columnDefinition = "TIMESTAMP WITH TIME ZONE", nullable = false)
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public LocalDateTime getCreatedAt() {
		return super.getCreatedAt();
	}

	/**
	 * @see org.coldis.library.model.AbstractTimestampedObject#getUpdatedAt()
	 */
	@Override
	@DtoAttribute(readOnly = true, usedInComparison = false)
	@Column(columnDefinition = "TIMESTAMP WITH TIME ZONE", nullable = false)
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public LocalDateTime getUpdatedAt() {
		return super.getUpdatedAt();
	}

	/**
	 * @see org.coldis.library.model.AbstractExpirableObject#getExpiredAt()
	 */
	@Override
	@DtoAttribute(readOnly = true, usedInComparison = false)
	@Column(columnDefinition = "TIMESTAMP WITH TIME ZONE")
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public LocalDateTime getExpiredAt() {
		return super.getExpiredAt();
	}

	/**
	 * @see org.coldis.library.model.AbstractExpirableObject#getExpiredByDefault()
	 */
	@Override
	@Transient
	@JsonIgnore
	@DtoAttribute(ignore = true)
	protected Boolean getExpiredByDefault() {
		return super.getExpiredByDefault();
	}

	/**
	 * @see org.coldis.library.model.AbstractExpirableObject#getExpired()
	 */
	@Override
	@DtoAttribute(readOnly = true)
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public Boolean getExpired() {
		return super.getExpired();
	}

	/**
	 * JPA usage only.
	 *
	 * @param expired Expired.
	 */
	protected void setExpired(final Boolean expired) {
	}

}