package com.finsight.auth.security;

import com.finsight.user.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads a user for the <em>login</em> path only.
 *
 * <p>Spring Security's {@code DaoAuthenticationProvider} calls this during
 * {@code POST /api/v1/auth/login}. It is deliberately <strong>not</strong> used by
 * {@link JwtAuthenticationFilter}: doing so would add a database query to every
 * authenticated request, which is exactly the cost a stateless JWT exists to avoid.
 *
 * <p>Constructor injection (no {@code @Autowired} field) keeps the dependency
 * explicit, makes the class trivially unit-testable with a mock repository, and
 * lets the field be {@code final}.
 */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public DatabaseUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * @param username the email address entered at login
     * @throws UsernameNotFoundException which {@code DaoAuthenticationProvider}
     *         converts into a {@code BadCredentialsException} when
     *         {@code hideUserNotFoundExceptions} is on (the default), so an
     *         unknown email and a wrong password are indistinguishable to the client
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        return userRepository.findByEmailIgnoreCase(username)
                .map(UserPrincipal::from)
                .orElseThrow(() -> new UsernameNotFoundException("No user for the supplied credentials"));
    }
}
