package com.theodomart.energy.tariff.serviceImpl;

import com.theodomart.energy.common.error.DuplicateResourceException;
import com.theodomart.energy.common.error.InvalidRequestException;
import com.theodomart.energy.common.persistence.ConstraintViolations;
import com.theodomart.energy.security.service.OwnershipService;
import com.theodomart.energy.tariff.config.TariffProperties;
import com.theodomart.energy.tariff.domain.TariffPlan;
import com.theodomart.energy.tariff.domain.TariffVersion;
import com.theodomart.energy.tariff.repository.TariffPlanRepository;
import com.theodomart.energy.tariff.repository.TariffVersionRepository;
import com.theodomart.energy.tariff.service.NewTariffVersion;
import com.theodomart.energy.tariff.service.TariffService;
import com.theodomart.energy.tariff.service.TariffVersionView;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TariffServiceImpl implements TariffService {

	private static final Logger log = LoggerFactory.getLogger(TariffServiceImpl.class);

	private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "effectiveFrom");

	private final TariffPlanRepository plans;

	private final TariffVersionRepository versions;

	private final OwnershipService ownership;

	private final TariffProperties properties;

	private final Clock clock;

	public TariffServiceImpl(TariffPlanRepository plans, TariffVersionRepository versions, OwnershipService ownership,
			TariffProperties properties, Clock clock) {
		this.plans = plans;
		this.versions = versions;
		this.ownership = ownership;
		this.properties = properties;
		this.clock = clock;
	}

	@Override
	@Transactional
	public TariffVersionView addVersion(UUID landlordId, UUID buildingId, NewTariffVersion command) {
		ownership.requireBuildingOwner(landlordId, buildingId);
		Instant now = clock.instant();
		plans.insertIfAbsent(UUID.randomUUID(), buildingId, TariffPlan.normaliseName(command.planName()), now);
		TariffPlan plan = plans.findByBuildingId(buildingId).orElseThrow();

		TariffVersion version;
		try {
			version = TariffVersion.create(plan.getId(), command.rateKoboPerKwh(), properties.vatBasisPoints(),
					properties.platformFeeBasisPoints(), command.effectiveFrom(), now);
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidRequestException(ex.getMessage() + ".");
		}
		try {
			version = versions.saveAndFlush(version);
		}
		catch (DataIntegrityViolationException ex) {
			if ("uk_tariff_versions_plan_effective_from".equals(ConstraintViolations.constraintName(ex))) {
				throw new DuplicateResourceException("A tariff version already starts at that time.");
			}
			throw ex;
		}
		log.info("Tariff version created: versionId={} buildingId={} effectiveFrom={}", version.getId(), buildingId,
				version.getEffectiveFrom());
		return toView(version, plan, activeVersionId(plan));
	}

	@Override
	@Transactional(readOnly = true)
	public Page<TariffVersionView> listVersions(UUID landlordId, UUID buildingId, int page, int size) {
		ownership.requireBuildingOwner(landlordId, buildingId);
		PageRequest request = PageRequest.of(page, size, NEWEST_FIRST);
		Optional<TariffPlan> plan = plans.findByBuildingId(buildingId);
		if (plan.isEmpty()) {
			return Page.empty(request);
		}
		UUID activeId = activeVersionId(plan.get());
		return versions.findByPlanId(plan.get().getId(), request)
			.map(version -> toView(version, plan.get(), activeId));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<TariffVersionView> findActiveVersion(UUID buildingId) {
		return plans.findByBuildingId(buildingId)
			.flatMap(plan -> activeVersion(plan).map(version -> toView(version, plan, version.getId())));
	}

	private UUID activeVersionId(TariffPlan plan) {
		return activeVersion(plan).map(TariffVersion::getId).orElse(null);
	}

	private Optional<TariffVersion> activeVersion(TariffPlan plan) {
		return versions.findFirstByPlanIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(plan.getId(),
				clock.instant());
	}

	private static TariffVersionView toView(TariffVersion v, TariffPlan plan, UUID activeId) {
		return new TariffVersionView(v.getId(), plan.getBuildingId(), plan.getName(), v.getRateKoboPerKwh(),
				v.getVatBasisPoints(), v.getPlatformFeeBasisPoints(), v.getEffectiveFrom(), v.getId().equals(activeId),
				v.getCreatedAt());
	}

}
