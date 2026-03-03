package com.finsense.core.api.controller

import com.finsense.core.dto.api.TransactionResponse
import com.finsense.core.model.TransactionStatus
import com.finsense.core.service.TransactionService
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class TransactionController(
    private val transactionService: TransactionService
) {
    @GetMapping("/users/{userId}/transactions")
    fun getTransactions(
        @PathVariable userId: UUID,
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) status: TransactionStatus?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int
    ): List<TransactionResponse> {
        return transactionService.findTransactions(
            userId = userId,
            category = category,
            status = status,
            from = from,
            to = to,
            page = page.coerceAtLeast(0),
            size = size.coerceIn(1, 200)
        )
    }
}
