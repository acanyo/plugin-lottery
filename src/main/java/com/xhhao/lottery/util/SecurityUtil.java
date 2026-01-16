package com.xhhao.lottery.util;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.extension.ReactiveExtensionClient;

public class SecurityUtil {

    public static Mono<User> getCurrentUser(ReactiveExtensionClient client) {
        return ReactiveSecurityContextHolder.getContext()
            .map(ctx -> ctx.getAuthentication())
            .filter(auth -> auth != null 
                && auth.isAuthenticated() 
                && !(auth instanceof AnonymousAuthenticationToken))
            .map(Authentication::getName)
            .filter(name -> name != null && !name.isBlank() && !"anonymousUser".equals(name))
            .flatMap(username -> client.get(User.class, username));
    }

    public static Mono<String> getCurrentUsername() {
        return ReactiveSecurityContextHolder.getContext()
            .map(ctx -> ctx.getAuthentication())
            .filter(auth -> auth != null 
                && auth.isAuthenticated() 
                && !(auth instanceof AnonymousAuthenticationToken))
            .map(Authentication::getName)
            .filter(name -> name != null && !name.isBlank() && !"anonymousUser".equals(name));
    }

    public static Mono<Boolean> isAuthenticated() {
        return getCurrentUsername()
            .map(name -> true)
            .defaultIfEmpty(false);
    }
}
