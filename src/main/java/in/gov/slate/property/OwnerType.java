package in.gov.slate.property;

import java.util.Arrays;
import java.util.Optional;

/**
 * Legal type of a property owner. The {@link Form} decides which identity
 * fields are collected; types without a dedicated form use {@link Form#DEFAULT}.
 */
public enum OwnerType {
    INDIVIDUAL(Form.INDIVIDUAL),
    SOLE_PROPRIETORSHIP(Form.DEFAULT),
    PARTNERSHIP_FIRM(Form.PARTNERSHIP_FIRM),
    HUF(Form.HUF),
    LLP(Form.LLP),
    PRIVATE_LIMITED_COMPANY(Form.COMPANY),
    PUBLIC_LIMITED_COMPANY(Form.COMPANY),
    ONE_PERSON_COMPANY(Form.COMPANY),
    TRUST(Form.TRUST),
    SOCIETY(Form.DEFAULT),
    AOP_BOI(Form.DEFAULT),
    GOVERNMENT(Form.DEFAULT),
    OTHER_LEGAL_ENTITY(Form.DEFAULT);

    public enum Form {
        INDIVIDUAL(null),
        DEFAULT(null),
        COMPANY("AUTHORISED_SIGNATORY"),
        PARTNERSHIP_FIRM("AUTHORISED_PARTNER"),
        HUF("KARTA"),
        LLP("AUTHORISED_PARTNER"),
        TRUST("AUTHORISED_TRUSTEE");

        private final String representativeRole;

        Form(String representativeRole) {
            this.representativeRole = representativeRole;
        }

        public String representativeRole() {
            return representativeRole;
        }

        public boolean ownerAadhaar() {
            return this == INDIVIDUAL || this == DEFAULT;
        }

        public boolean ownerMobile() {
            return this == INDIVIDUAL || this == DEFAULT;
        }

        public boolean registrationNo() {
            return this == COMPANY || this == PARTNERSHIP_FIRM || this == LLP || this == TRUST;
        }

        public boolean representativeDesignation() {
            return this == COMPANY;
        }

        public boolean representativeMobile() {
            return representativeRole != null && this != HUF;
        }
    }

    private final Form form;

    OwnerType(Form form) {
        this.form = form;
    }

    public Form form() {
        return form;
    }

    public static Optional<OwnerType> fromCode(String code) {
        return Arrays.stream(values()).filter(type -> type.name().equals(code)).findFirst();
    }
}
