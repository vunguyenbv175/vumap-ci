package vn.vumap.signs;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** SAF import validation skeleton tests (P1 gate 3 slice). */
public final class SignPackValidatorTest {

    private SignPackValidatorTest() {
    }

    public static int runAll() {
        int passed = 0;
        passed = check("acceptsValidPack", SignPackValidatorTest::acceptsValidPack, passed);
        passed = check("rejectsUnsupportedSchema", SignPackValidatorTest::rejectsUnsupportedSchema, passed);
        passed = check("rejectsMissingProvenance", SignPackValidatorTest::rejectsMissingProvenance, passed);
        passed = check("rejectsOutOfVnRange", SignPackValidatorTest::rejectsOutOfVnRange, passed);
        passed = check("rejectsDuplicateId", SignPackValidatorTest::rejectsDuplicateId, passed);
        passed = check("rejectsTooLarge", SignPackValidatorTest::rejectsTooLarge, passed);
        passed = check("rejectsTruncated", SignPackValidatorTest::rejectsTruncated, passed);
        passed = check("shaRoundtripAndRollback", SignPackValidatorTest::shaRoundtripAndRollback, passed);
        passed = check("rejectsWrongChecksum", SignPackValidatorTest::rejectsWrongChecksum, passed);
        passed = check("rejectsZoneSignWithValue", SignPackValidatorTest::rejectsZoneSignWithValue, passed);
        return passed;
    }

    private static int check(String name, Runnable test, int passed) {
        try {
            test.run();
            System.out.println("PASS SignPackValidatorTest." + name);
            return passed + 1;
        } catch (AssertionError e) {
            System.out.println("FAIL SignPackValidatorTest." + name + " :: " + e.getMessage());
            throw e;
        }
    }

    static List<PackPoint> validPoints() {
        return Arrays.asList(
                new PackPoint("S01", SignType.R420, 10.82310, 106.62970, 90.0, null, "E_Q1", 0.9, "OFFLINE_PACK"),
                new PackPoint("S02", SignType.P127, 10.82312, 106.63010, 90.0, 60, "E_Q1", 0.85, "OFFLINE_PACK"),
                new PackPoint("S03", SignType.R421, 10.82315, 106.63500, 90.0, null, "E_Q2", 0.9, "OFFLINE_PACK"));
    }

    static PackManifest validManifest() {
        return new PackManifest(1, "synthetic-vumap-p1", "synthetic-test-only", "p1-test", null);
    }

    static void acceptsValidPack() {
        ValidationResult result =
                SignPackValidator.validate(validManifest(), validPoints(), 2048);
        Assert.isTrue(result.isOk(), "valid pack must pass: " + result.errors());
        Assert.isTrue(result.applyNew(), "valid pack applies");
    }

    static void rejectsUnsupportedSchema() {
        PackManifest manifest =
                new PackManifest(99, "synthetic-vumap-p1", "synthetic-test-only", "p1-test", null);
        ValidationResult result = SignPackValidator.validate(manifest, validPoints(), 2048);
        Assert.isTrue(!result.isOk(), "bad schema must reject");
        Assert.isTrue(result.keepOldDb(), "reject must keep old DB (rollback)");
    }

    static void rejectsMissingProvenance() {
        PackManifest manifest = new PackManifest(1, "", "", "", null);
        ValidationResult result = SignPackValidator.validate(manifest, validPoints(), 2048);
        Assert.isTrue(!result.isOk(), "missing provenance must reject");
    }

    static void rejectsOutOfVnRange() {
        List<PackPoint> points = new ArrayList<>(validPoints());
        points.add(new PackPoint("BAD", SignType.R420, 40.0, 116.0, 90.0, null, "E_X", 0.9, "OFFLINE_PACK"));
        ValidationResult result = SignPackValidator.validate(validManifest(), points, 2048);
        Assert.isTrue(!result.isOk(), "out-of-VN coordinate must reject");
    }

    static void rejectsDuplicateId() {
        List<PackPoint> points = new ArrayList<>(validPoints());
        points.add(new PackPoint("S01", SignType.P127, 10.83, 106.64, 90.0, 60, "E_Q2", 0.8, "OFFLINE_PACK"));
        ValidationResult result = SignPackValidator.validate(validManifest(), points, 2048);
        Assert.isTrue(!result.isOk(), "duplicate id must reject");
        Assert.isTrue(result.keepOldDb(), "duplicate must keep old DB");
    }

    static void rejectsTooLarge() {
        ValidationResult result = SignPackValidator.validate(
                validManifest(), validPoints(), SignPackValidator.MAX_BYTES + 1);
        Assert.isTrue(!result.isOk(), "oversize file must reject");
    }

    static void rejectsTruncated() {
        ValidationResult empty = SignPackValidator.validate(
                validManifest(), new ArrayList<PackPoint>(), 0);
        Assert.isTrue(!empty.isOk(), "empty/truncated pack must reject");
        Assert.isTrue(empty.keepOldDb(), "truncated must keep old DB");

        List<PackPoint> missingEdge = new ArrayList<>();
        missingEdge.add(new PackPoint("S01", SignType.R420, 10.82, 106.62, 90.0, null, "", 0.9, "OFFLINE_PACK"));
        ValidationResult missing =
                SignPackValidator.validate(validManifest(), missingEdge, 512);
        Assert.isTrue(!missing.isOk(), "missing edge must reject");
    }

    static void shaRoundtripAndRollback() {
        byte[] content = "vumap-p1-fixture".getBytes(StandardCharsets.UTF_8);
        String hex = SignPackValidator.sha256Hex(content);
        Assert.isTrue(SignPackValidator.verifySha256(content, hex), "SHA roundtrip must verify");
        Assert.isTrue(!SignPackValidator.verifySha256("tampered".getBytes(StandardCharsets.UTF_8), hex),
                "corrupted bytes must fail SHA (rollback keeps old DB)");
    }

    static void rejectsWrongChecksum() {
        byte[] content = "vumap-p1-fixture".getBytes(StandardCharsets.UTF_8);
        String wrongHex = SignPackValidator.sha256Hex(
                "vumap-p1-tampered".getBytes(StandardCharsets.UTF_8));
        // Otherwise-valid pack with a deliberately wrong declared checksum.
        PackManifest manifest = new PackManifest(
                1, "synthetic-vumap-p1", "synthetic-test-only", "p1-test", wrongHex);
        ValidationResult mismatch = SignPackValidator.validate(
                manifest, validPoints(), content.length, content);
        Assert.isTrue(!mismatch.isOk(), "checksum mismatch must reject pack acceptance");
        Assert.isTrue(mismatch.errors().contains("CHECKSUM_MISMATCH"), "must name CHECKSUM_MISMATCH");
        Assert.isTrue(mismatch.keepOldDb(), "checksum mismatch must keep old DB");
        Assert.isTrue(!mismatch.applyNew(), "checksum mismatch must not apply new pack");
        // Declared checksum with no bytes to verify fails closed too.
        ValidationResult unverified = SignPackValidator.validate(manifest, validPoints(), 2048);
        Assert.isTrue(!unverified.isOk(), "declared checksum without bytes must reject");
        Assert.isTrue(unverified.errors().contains("CHECKSUM_UNVERIFIED"), "must name CHECKSUM_UNVERIFIED");
        Assert.isTrue(unverified.keepOldDb(), "unverified checksum must keep old DB");
        // Matching checksum accepts.
        String rightHex = SignPackValidator.sha256Hex(content);
        PackManifest good = new PackManifest(
                1, "synthetic-vumap-p1", "synthetic-test-only", "p1-test", rightHex);
        ValidationResult accepted = SignPackValidator.validate(
                good, validPoints(), content.length, content);
        Assert.isTrue(accepted.isOk(), "matching checksum must accept: " + accepted.errors());
    }

    static void rejectsZoneSignWithValue() {
        List<PackPoint> points = new ArrayList<>();
        points.add(new PackPoint("S01", SignType.R420, 10.82, 106.62, 90.0, 60, "E_Q1", 0.9, "OFFLINE_PACK"));
        ValidationResult result = SignPackValidator.validate(validManifest(), points, 512);
        Assert.isTrue(!result.isOk(), "R420 carrying a km/h value must reject");
    }
}
