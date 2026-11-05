package com.theodomart.energy.meter.serviceImpl;

import com.theodomart.energy.common.domain.Role;
import com.theodomart.energy.common.error.DuplicateResourceException;
import com.theodomart.energy.common.error.InvalidRequestException;
import com.theodomart.energy.common.error.ResourceNotFoundException;
import com.theodomart.energy.common.persistence.ConstraintViolations;
import com.theodomart.energy.meter.domain.GatewayType;
import com.theodomart.energy.meter.domain.Meter;
import com.theodomart.energy.meter.repository.MeterRepository;
import com.theodomart.energy.meter.service.MeterService;
import com.theodomart.energy.meter.service.MeterView;
import com.theodomart.energy.meter.service.RegisterMeterCommand;
import com.theodomart.energy.property.service.TenancyService;
import com.theodomart.energy.security.service.CurrentUser;
import com.theodomart.energy.security.service.OwnershipService;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MeterServiceImpl implements MeterService {

	/** 5 kWh, a few days of use for a typical flat. Landlords can set their own when registering. */
	static final long DEFAULT_LOW_CREDIT_THRESHOLD_WH = 5_000;

	private static final Logger log = LoggerFactory.getLogger(MeterServiceImpl.class);

	private final MeterRepository meters;

	private final OwnershipService ownership;

	private final TenancyService tenancies;

	private final Clock clock;

	public MeterServiceImpl(MeterRepository meters, OwnershipService ownership, TenancyService tenancies,
			Clock clock) {
		this.meters = meters;
		this.ownership = ownership;
		this.tenancies = tenancies;
		this.clock = clock;
	}

	@Override
	@Transactional
	public MeterView register(UUID landlordId, RegisterMeterCommand command) {
		long threshold = command.lowCreditThresholdWh() == null ? DEFAULT_LOW_CREDIT_THRESHOLD_WH
				: command.lowCreditThresholdWh();
		Meter meter;
		try {
			meter = Meter.register(landlordId, command.serialNumber(), GatewayType.valueOf(command.gatewayType()),
					command.externalDeviceId(), threshold, clock.instant());
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidRequestException(ex.getMessage() + ".");
		}
		try {
			meters.saveAndFlush(meter);
		}
		catch (DataIntegrityViolationException ex) {
			if ("uk_meters_serial_number".equals(ConstraintViolations.constraintName(ex))) {
				throw new DuplicateResourceException("A meter with this serial number is already registered.");
			}
			throw ex;
		}
		log.info("Meter registered: meterId={} landlordId={}", meter.getId(), landlordId);
		return toView(meter);
	}

	@Override
	@Transactional
	public MeterView assignToUnit(UUID landlordId, UUID meterId, UUID unitId) {
		Meter meter = meters.findById(meterId)
			.filter(found -> found.getLandlordId().equals(landlordId))
			.orElseThrow(() -> notFound(meterId));
		ownership.requireUnitOwner(landlordId, unitId);
		try {
			meter.assignToUnit(unitId, clock.instant());
			// The UNIQUE constraint decides who gets the unit: a lookup first would race with a concurrent assign.
			meters.flush();
		}
		catch (IllegalStateException ex) {
			throw new DuplicateResourceException("This meter is already assigned to another unit.");
		}
		catch (DataIntegrityViolationException ex) {
			if ("uk_meters_unit_id".equals(ConstraintViolations.constraintName(ex))) {
				throw new DuplicateResourceException("This unit already has a meter.");
			}
			throw ex;
		}
		log.info("Meter assigned: meterId={} unitId={}", meterId, unitId);
		return toView(meter);
	}

	@Override
	@Transactional(readOnly = true)
	public MeterView get(CurrentUser user, UUID meterId) {
		return meters.findById(meterId)
			.filter(meter -> canRead(user, meter))
			.map(MeterServiceImpl::toView)
			.orElseThrow(() -> notFound(meterId));
	}

	private boolean canRead(CurrentUser user, Meter meter) {
		if (user.role() == Role.LANDLORD) {
			return meter.getLandlordId().equals(user.id());
		}
		return user.role() == Role.TENANT && meter.getUnitId() != null
				&& tenancies.isActiveTenant(user.id(), meter.getUnitId());
	}

	private static ResourceNotFoundException notFound(UUID meterId) {
		return new ResourceNotFoundException("Meter " + meterId + " was not found.");
	}

	private static MeterView toView(Meter m) {
		return new MeterView(m.getId(), m.getSerialNumber(), m.getGatewayType().name(), m.getUnitId(),
				m.getStatus().name(), m.getLastRegisterWh(), m.getLastReadingAt(), m.getCreditBalanceWh(),
				m.getLowCreditThresholdWh(), m.getCreatedAt());
	}

}
