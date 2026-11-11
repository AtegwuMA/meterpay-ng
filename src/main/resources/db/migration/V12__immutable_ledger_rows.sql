-- Journal entries and postings are a permanent record (DESIGN 6.3): a mistake is fixed by a new correcting entry,
-- never by changing an old one. ArchUnit rule 4 only stops the Java code from deleting them. This makes the database
-- refuse an UPDATE or DELETE from anywhere, including a script or a hand-written query.
--
-- Per statement, not per row: one call however many rows match, and it refuses even a statement that matches none.
-- TRUNCATE is left alone, because statement triggers on UPDATE and DELETE do not fire for it. Tests use it to clean
-- up. This is a guard against mistakes, not against the table owner, who can still disable the trigger: production
-- should run the application as a role that does not own these tables.
--
-- SQLSTATE 23001 (restrict_violation) is an integrity error, so Spring reports it as a DataIntegrityViolationException
-- like any other constraint.
CREATE FUNCTION refuse_ledger_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'ledger_immutable: % on % is not allowed, post a correcting entry instead', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER tr_journal_entries_immutable
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_ledger_change();

CREATE TRIGGER tr_postings_immutable
    BEFORE UPDATE OR DELETE ON postings
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_ledger_change();
