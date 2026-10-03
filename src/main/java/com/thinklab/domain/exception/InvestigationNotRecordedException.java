package com.thinklab.domain.exception;

/** The lookup was refused because the ledger would not take its record: an unrecorded lookup is not allowed to happen (ADR-027). */
public class InvestigationNotRecordedException extends BusinessException {

    public InvestigationNotRecordedException(Throwable cause) {
        super("ERR-GTW-00502", "The lookup was refused because it could not be recorded on the compliance ledger; nothing was disclosed.", cause);
    }
}
