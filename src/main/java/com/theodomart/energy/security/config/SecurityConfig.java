package com.theodomart.energy.security.config;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) throws Exception {
		ProblemDetailSecurityHandlers problemHandlers = new ProblemDetailSecurityHandlers(resolver);
		return http
			// Stateless bearer-token API: no cookies, so no CSRF exposure and no server-side session.
			.csrf(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
						"/api/v1/auth/refresh")
				.permitAll()
				.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
				.permitAll()
				.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
				.permitAll()
				.requestMatchers("/error")
				.permitAll()
				.anyRequest()
				.authenticated())
			.oauth2ResourceServer(oauth -> oauth
				.jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(new JwtCurrentUserConverter()))
				.authenticationEntryPoint(problemHandlers)
				.accessDeniedHandler(problemHandlers))
			.exceptionHandling(ex -> ex.authenticationEntryPoint(problemHandlers).accessDeniedHandler(problemHandlers))
			.build();
	}

	@Bean
	JwtEncoder jwtEncoder(JwtProperties properties) {
		return JwtKeys.encoder(properties);
	}

	@Bean
	JwtDecoder jwtDecoder(JwtProperties properties, Clock clock) {
		return JwtKeys.decoder(properties, clock);
	}

	/** DESIGN 11 sets strength 12 (about 250 ms a hash). The test profile lowers it to keep the suite fast. */
	@Bean
	PasswordEncoder passwordEncoder(@Value("${meterpay.security.bcrypt-strength:12}") int strength) {
		return new BCryptPasswordEncoder(strength);
	}

}
