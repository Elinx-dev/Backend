package in.gov.slate.common;

public final class RevenueLandContext {

    private RevenueLandContext() {
    }

    public static String forLegacySnapshot(Object landType) {
        if (landType == null) {
            return null;
        }
        String value = landType.toString();
        return "RURAL".equals(value) || "NATHAM".equals(value) ? value : null;
    }
}
