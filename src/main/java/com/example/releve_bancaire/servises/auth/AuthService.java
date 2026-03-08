package com.example.releve_bancaire.servises.auth;

import com.example.releve_bancaire.entity.auth.UserAccount;
import com.example.releve_bancaire.repository.UserAccountDao;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserAccountDao userAccountDao;

    public Optional<UserAccount> authenticate(String username, String password) {
        if (username == null || password == null) {
            return Optional.empty();
        }
        return userAccountDao.findByUsername(username)
                .filter(user -> !Boolean.FALSE.equals(user.getActive()))
                .filter(user -> user.getRole() != null)
                .filter(user -> Objects.equals(user.getPassword(), password));
    }

    public SessionUser toSessionUser(UserAccount user) {
        return new SessionUser(
                user.getId(),
                user.getUsername(),
                user.getRole(),
                user.getDisplayName());
    }

    public void setSessionUser(HttpSession session, UserAccount user) {
        session.setAttribute(SessionKeys.USER_ID, user.getId());
        session.setAttribute(SessionKeys.USERNAME, user.getUsername());
        session.setAttribute(SessionKeys.ROLE, user.getRole().name());
        session.setAttribute(SessionKeys.DISPLAY_NAME, user.getDisplayName());
    }

    public void clearSession(HttpSession session) {
        session.removeAttribute(SessionKeys.USER_ID);
        session.removeAttribute(SessionKeys.USERNAME);
        session.removeAttribute(SessionKeys.ROLE);
        session.removeAttribute(SessionKeys.DISPLAY_NAME);
    }

    public SessionUser requireSessionUser(HttpSession session) {
        Object userId = session.getAttribute(SessionKeys.USER_ID);
        Object username = session.getAttribute(SessionKeys.USERNAME);
        Object role = session.getAttribute(SessionKeys.ROLE);
        Object displayName = session.getAttribute(SessionKeys.DISPLAY_NAME);

        if (userId == null || role == null || username == null) {
            return null;
        }

        Long idValue;
        com.example.releve_bancaire.entity.auth.UserRole roleValue;
        try {
            idValue = userId instanceof Long ? (Long) userId : Long.valueOf(userId.toString());
            roleValue = com.example.releve_bancaire.entity.auth.UserRole.valueOf(role.toString());
        } catch (RuntimeException ex) {
            return null;
        }

        return new SessionUser(
                idValue,
                username.toString(),
                roleValue,
                displayName != null ? displayName.toString() : null);
    }
}
