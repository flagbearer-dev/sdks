package dev.flagbearer;

import java.util.Objects;

/** Who a flag is checked for. Both fields are optional; null or empty means unset. */
public final class User {
    private static final User ANONYMOUS = new User(null, null);

    private final String key;
    private final String email;

    private User(String key, String email) {
        this.key = key;
        this.email = email;
    }

    /** A user with a stable key, such as their ID. */
    public static User of(String key) {
        return new User(key, null);
    }

    /** A user with a key and an email. Either may be null. */
    public static User of(String key, String email) {
        return new User(key, email);
    }

    /** A user with neither key nor email. */
    public static User anonymous() {
        return ANONYMOUS;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getKey() {
        return key;
    }

    public String getEmail() {
        return email;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof User)) {
            return false;
        }
        User user = (User) other;
        return Objects.equals(key, user.key) && Objects.equals(email, user.email);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, email);
    }

    @Override
    public String toString() {
        // Never prints user data, so a logged User doesn't leak it.
        return "User{key=" + (key == null ? "unset" : "set") + ", email=" + (email == null ? "unset" : "set") + "}";
    }

    public static final class Builder {
        private String key;
        private String email;

        private Builder() {}

        public Builder key(String key) {
            this.key = key;
            return this;
        }

        public Builder email(String email) {
            this.email = email;
            return this;
        }

        public User build() {
            return new User(key, email);
        }
    }
}
