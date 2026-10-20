package vn.vumap.signs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Import validation outcome. A rejected pack must keep the previous DB
 * (atomic swap + rollback): {@code applyNew == false} and
 * {@code keepOldDb == true}.
 */
public final class ValidationResult {
    private final boolean ok;
    private final List<String> errors;
    private final boolean applyNew;
    private final boolean keepOldDb;

    private ValidationResult(boolean ok, List<String> errors) {
        this.ok = ok;
        this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
        this.applyNew = ok;
        this.keepOldDb = !ok;
    }

    public static ValidationResult ok() {
        return new ValidationResult(true, Collections.<String>emptyList());
    }

    public static ValidationResult reject(List<String> errors) {
        return new ValidationResult(false, errors);
    }

    public boolean isOk() {
        return ok;
    }

    public List<String> errors() {
        return errors;
    }

    public boolean applyNew() {
        return applyNew;
    }

    public boolean keepOldDb() {
        return keepOldDb;
    }
}
