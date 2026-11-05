package com.theodomart.energy.meter.repository;

import com.theodomart.energy.meter.domain.Meter;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeterRepository extends JpaRepository<Meter, UUID> {

}
