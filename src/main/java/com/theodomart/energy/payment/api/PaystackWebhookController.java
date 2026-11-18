package com.theodomart.energy.payment.api;

import com.theodomart.energy.payment.service.PaystackWebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Called by Paystack, not by a user, so there is no token: the signature is the authentication (DESIGN 11). The
 * path is open in SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/webhooks/paystack")
public class PaystackWebhookController {

	static final String SIGNATURE_HEADER = "x-paystack-signature";

	private final PaystackWebhookService webhooks;

	public PaystackWebhookController(PaystackWebhookService webhooks) {
		this.webhooks = webhooks;
	}

	/** The body as bytes, never as a parsed object: re-serialised JSON would not match the signature. */
	@PostMapping
	public ResponseEntity<Void> receive(@RequestBody(required = false) byte[] body,
			@RequestHeader(name = SIGNATURE_HEADER, required = false) String signature) {
		webhooks.receive(body, signature);
		return ResponseEntity.ok().build();
	}

}
