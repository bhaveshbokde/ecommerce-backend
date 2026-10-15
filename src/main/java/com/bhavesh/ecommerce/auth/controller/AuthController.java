package com.bhavesh.ecommerce.auth.controller;

import com.bhavesh.ecommerce.auth.dto.AuthResponse;
import com.bhavesh.ecommerce.auth.dto.LoginRequest;
import com.bhavesh.ecommerce.auth.dto.RefreshRequest;
import com.bhavesh.ecommerce.auth.dto.RegisterRequest;
import com.bhavesh.ecommerce.auth.service.AuthService;
import com.bhavesh.ecommerce.security.UserPrincipal;
import com.bhavesh.ecommerce.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registration, login, refresh and logout.
 *
 * <p>Thin on purpose. A controller's job is HTTP: bind, validate, delegate, choose
 * a status code. Every decision that matters is in {@link AuthService}, which is
 * what makes those decisions unit-testable without a servlet container.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Account creation and token management")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @SecurityRequirements   // public: overrides the global bearer requirement
    @Operation(summary = "Register a customer account",
            description = "Always creates a ROLE_CUSTOMER account. There is no field "
                    + "through which a different role can be requested.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created; tokens issued"),
            @ApiResponse(responseCode = "400", description = "Validation failed", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "409", description = "Email already registered", content = @io.swagger.v3.oas.annotations.media.Content)
    })
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Exchange credentials for tokens",
            description = "Returns the same 401 for an unknown email and a wrong password, "
                    + "and takes the same time, so the endpoint cannot be used to "
                    + "enumerate registered addresses.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tokens issued"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials", content = @io.swagger.v3.oas.annotations.media.Content)
    })
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    @Operation(summary = "Exchange a refresh token for a new pair",
            description = "The presented refresh token is revoked and replaced. Reusing a "
                    + "rotated token fails, which is how a stolen token surfaces.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "New tokens issued"),
            @ApiResponse(responseCode = "401", description = "Token invalid, expired or revoked", content = @io.swagger.v3.oas.annotations.media.Content)
    })
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }

    @PostMapping("/logout")
    @SecurityRequirements
    @Operation(summary = "Revoke a refresh token",
            description = "Always 204, including for an unknown token — answering 404 would "
                    + "confirm which tokens exist. The current access token remains valid "
                    + "until it expires; that is inherent to stateless JWTs.")
    @ApiResponse(responseCode = "204", description = "Revoked, or the token was already unusable")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    @Operation(summary = "Revoke every refresh token for the caller",
            description = "Signs the account out of every device. Requires a valid access token.")
    @ApiResponse(responseCode = "204", description = "All refresh tokens revoked")
    public ResponseEntity<Void> logoutEverywhere(@AuthenticationPrincipal UserPrincipal principal) {
        authService.logoutEverywhere(principal.getId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    @Operation(summary = "The authenticated account",
            description = "Re-reads the user from the database rather than echoing the token's "
                    + "claims, so a role change or a disablement is visible here immediately.")
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(authService.currentUser(principal.getId()));
    }
}
