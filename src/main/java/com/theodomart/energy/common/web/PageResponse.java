package com.theodomart.energy.common.web;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * The page shape every list endpoint returns (DESIGN 9). Spring's Page is not serialised directly: its JSON
 * form is not a stable contract and exposes internals such as sort and pageable details.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements) {

	public static <S, T> PageResponse<T> from(Page<S> page, Function<S, T> mapper) {
		return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
				page.getTotalElements());
	}

}
