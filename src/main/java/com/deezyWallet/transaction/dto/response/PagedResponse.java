package com.deezyWallet.transaction.dto.response;

import java.util.List;

import org.springframework.data.domain.Page;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Generic paginated response wrapper.
 * Used for transaction history endpoints returning Page<TransactionResponse>.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PagedResponse<T> {

	private List<T>  content;
	private int      page;
	private int      size;
	private long     totalElements;
	private int      totalPages;
	private boolean  last;

	public static <T> PagedResponse<T> from(Page<T> page) {
		return PagedResponse.<T>builder()
				.content(page.getContent())
				.page(page.getNumber())
				.size(page.getSize())
				.totalElements(page.getTotalElements())
				.totalPages(page.getTotalPages())
				.last(page.isLast())
				.build();
	}
}
