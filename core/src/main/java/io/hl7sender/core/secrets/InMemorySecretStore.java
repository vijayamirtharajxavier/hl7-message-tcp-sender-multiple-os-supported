package io.hl7sender.core.secrets;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps secrets in memory only. For tests and for runs where nothing should be persisted. */
public final class InMemorySecretStore implements SecretStore {

    private final Map<String, String> secrets = new ConcurrentHashMap<>();

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(secrets.get(key));
    }

    @Override
    public void put(String key, String value) {
        secrets.put(key, value);
    }

    @Override
    public void delete(String key) {
        secrets.remove(key);
    }

    @Override
    public String description() {
        return "memory (not persisted)";
    }

    @Override
    public boolean isOsKeychain() {
        return false;
    }
}
