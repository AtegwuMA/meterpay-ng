package com.theodomart.energy.property.serviceImpl;

import com.theodomart.energy.common.error.DuplicateResourceException;
import com.theodomart.energy.common.error.ResourceNotFoundException;
import com.theodomart.energy.common.persistence.ConstraintViolations;
import com.theodomart.energy.property.domain.InviteCode;
import com.theodomart.energy.property.domain.Tenancy;
import com.theodomart.energy.property.domain.TenancyStatus;
import com.theodomart.energy.property.domain.TenantInvite;
import com.theodomart.energy.property.repository.TenancyRepository;
import com.theodomart.energy.property.repository.TenantInviteRepository;
import com.theodomart.energy.property.service.ActiveTenancy;
import com.theodomart.energy.property.service.InvalidInviteCodeException;
import com.theodomart.energy.property.service.InviteView;
import com.theodomart.energy.property.service.TenancyService;
import com.theodomart.energy.property.service.TenancyView;
import com.theodomart.energy.property.service.UnitOccupiedException;
import com.theodomart.energy.security.service.OwnershipService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenancyServiceImpl implements TenancyService {

	static final Duration INVITE_TTL = Duration.ofDays(7);

	// starts_on and ends_on are calendar dates as the landlord and tenant see them. At 23:30 UTC it is already
	// tomorrow in Lagos, so deriving them from UTC would record the wrong day.
	private static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Lagos");

	private static final Logger log = LoggerFactory.getLogger(TenancyServiceImpl.class);

	private final TenantInviteRepository invites;

	private final TenancyRepository tenancies;

	private final OwnershipService ownership;

	private final Clock clock;

	public TenancyServiceImpl(TenantInviteRepository invites, TenancyRepository tenancies, OwnershipService ownership,
			Clock clock) {
		this.invites = invites;
		this.tenancies = tenancies;
		this.ownership = ownership;
		this.clock = clock;
	}

	@Override
	@Transactional
	public InviteView createInvite(UUID landlordId, UUID unitId) {
		ownership.requireUnitOwner(landlordId, unitId);
		if (tenancies.existsByUnitIdAndStatus(unitId, TenancyStatus.ACTIVE)) {
			throw new UnitOccupiedException();
		}
		Instant now = clock.instant();
		invites.revokeOpenInvites(unitId, now);
		String code = InviteCode.generate();
		TenantInvite invite = TenantInvite.create(unitId, InviteCode.hash(code), landlordId, now, INVITE_TTL);
		try {
			invites.saveAndFlush(invite);
		}
		catch (DataIntegrityViolationException ex) {
			if ("uk_tenant_invites_one_open_per_unit".equals(ConstraintViolations.constraintName(ex))) {
				throw new DuplicateResourceException("Another invite for this unit was just created. Try again.");
			}
			throw ex;
		}
		log.info("Tenant invite created: inviteId={} unitId={}", invite.getId(), unitId);
		return new InviteView(code, unitId, invite.getExpiresAt());
	}

	// MANDATORY: redeeming alone would leave a used code with no account behind it if registration then failed.
	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public TenancyView redeemInvite(String code, UUID tenantId) {
		Instant now = clock.instant();
		TenantInvite invite = invites.findByCodeHashForUpdate(InviteCode.hash(code))
			.filter(found -> found.isRedeemable(now))
			.orElseThrow(InvalidInviteCodeException::new);
		Tenancy tenancy = Tenancy.start(invite.getUnitId(), tenantId, today(now), now);
		try {
			// The partial unique index is what guarantees one active tenancy per unit, not any earlier check.
			tenancies.saveAndFlush(tenancy);
		}
		catch (DataIntegrityViolationException ex) {
			if ("uk_tenancies_one_active_per_unit".equals(ConstraintViolations.constraintName(ex))) {
				throw new UnitOccupiedException();
			}
			throw ex;
		}
		invite.accept(tenantId, now);
		log.info("Tenancy started: tenancyId={} unitId={} tenantId={}", tenancy.getId(), tenancy.getUnitId(), tenantId);
		return toView(tenancy);
	}

	@Override
	@Transactional
	public TenancyView endTenancy(UUID landlordId, UUID unitId) {
		ownership.requireUnitOwner(landlordId, unitId);
		Tenancy tenancy = tenancies.findByUnitIdAndStatus(unitId, TenancyStatus.ACTIVE)
			.orElseThrow(() -> new ResourceNotFoundException("Unit " + unitId + " has no active tenancy."));
		Instant now = clock.instant();
		tenancy.end(today(now), now);
		// Flush now so a concurrent change surfaces here as an optimistic lock failure (409).
		tenancies.flush();
		log.info("Tenancy ended: tenancyId={} unitId={}", tenancy.getId(), unitId);
		return toView(tenancy);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isActiveTenant(UUID userId, UUID unitId) {
		return tenancies.existsByUnitIdAndTenantIdAndStatus(unitId, userId, TenancyStatus.ACTIVE);
	}

	@Override
	@Transactional(readOnly = true)
	public List<ActiveTenancy> findActiveTenancies(UUID tenantId) {
		return tenancies.findWithNamesByTenantId(tenantId, TenancyStatus.ACTIVE);
	}

	private static LocalDate today(Instant now) {
		return LocalDate.ofInstant(now, BUSINESS_ZONE);
	}

	private static TenancyView toView(Tenancy t) {
		return new TenancyView(t.getId(), t.getUnitId(), t.getTenantId(), t.getStartsOn(), t.getEndsOn(),
				t.getStatus().name());
	}

}
