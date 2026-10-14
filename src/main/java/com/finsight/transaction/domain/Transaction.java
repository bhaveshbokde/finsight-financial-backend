package com.finsight.transaction.domain;

import com.finsight.category.domain.Category;
import com.finsight.common.domain.AuditableEntity;
import com.finsight.common.util.Hashes;
import com.finsight.common.util.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One income or expense record.
 *
 * <h2>Money</h2>
 * {@code amount} is a {@link BigDecimal} mapped to {@code NUMERIC(19,4)} and is
 * always positive — {@link TransactionType} carries the direction. A
 * {@code double} would be wrong here: 0.1 has no exact binary representation, and
 * the error accumulates across a year of summed rows.
 *
 * <h2>Date</h2>
 * {@code txnDate} is a {@link LocalDate}, not an instant. A purchase happens on a
 * calendar day from the user's point of view; storing an instant would make
 * "March spending" depend on the reader's time zone and put transactions near
 * midnight in the wrong month.
 *
 * <h2>Associations</h2>
 * {@code category} is a {@code @ManyToOne(fetch = LAZY)} because the API returns
 * the category name alongside each transaction, so the relationship is genuinely
 * navigated. {@code LAZY} is explicit: {@code @ManyToOne} defaults to {@code EAGER},
 * which would issue an extra SELECT per row on list endpoints — the classic N+1.
 * List queries use {@code JOIN FETCH} to load both in one statement.
 *
 * <p>{@code userId} stays a raw {@code Long} — the owner is never navigated to,
 * only compared.
 */
@Entity
@Table(name = "transactions")
public class Transaction extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    /** Set only for rows created by a CSV import. */
    @Column(name = "import_job_id")
    private Long importJobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10)
    private TransactionType type;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "txn_date", nullable = false)
    private LocalDate txnDate;

    @Column(name = "description", nullable = false, length = 255)
    private String description;

    @Column(name = "merchant", length = 120)
    private String merchant;

    @Column(name = "notes", length = 500)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private TransactionSource source = TransactionSource.MANUAL;

    /**
     * Per-user idempotency key. Null for manual entries.
     *
     * <p>For an imported row this is {@code sha256(fileChecksum + ':' + rowNumber)}.
     * The partial unique index {@code uq_transactions_user_external_ref} means a
     * redelivered Kafka message, or a re-upload of the same file, inserts nothing
     * the second time. {@code updatable = false} because rewriting an idempotency
     * key would defeat its purpose.
     */
    @Column(name = "external_ref", length = 100, updatable = false)
    private String externalRef;

    /**
     * Content fingerprint used to <em>report</em> likely duplicates.
     *
     * <p>Deliberately not unique: two identical coffees on the same day are
     * legitimate data, and a unique constraint here would reject the second one.
     */
    @Column(name = "dedupe_hash", nullable = false, length = 64)
    private String dedupeHash;

    protected Transaction() {
    }

    private Transaction(Long userId, TransactionType type, BigDecimal amount, LocalDate txnDate,
                        String description, String merchant, Category category) {
        this.userId = userId;
        this.type = type;
        this.amount = Money.normalize(amount);
        this.txnDate = txnDate;
        this.description = description;
        this.merchant = merchant;
        this.category = category;
        this.source = TransactionSource.MANUAL;
        this.dedupeHash = computeDedupeHash();
    }

    /** Factory for a transaction entered through the API. */
    public static Transaction manual(Long userId, TransactionType type, BigDecimal amount,
                                     LocalDate txnDate, String description, String merchant,
                                     Category category) {
        return new Transaction(Objects.requireNonNull(userId, "userId"),
                type, amount, txnDate, description, merchant, category);
    }

    /**
     * Factory for a transaction produced by a CSV import.
     *
     * @param externalRef the idempotency key; see the field's documentation
     */
    public static Transaction imported(Long userId, TransactionType type, BigDecimal amount,
                                       LocalDate txnDate, String description, String merchant,
                                       Category category, Long importJobId, String externalRef) {
        Transaction tx = new Transaction(userId, type, amount, txnDate, description, merchant, category);
        tx.source = TransactionSource.CSV_IMPORT;
        tx.importJobId = importJobId;
        tx.externalRef = externalRef;
        return tx;
    }

    /**
     * Recomputes the duplicate fingerprint from the current field values.
     *
     * <p>Called from the factory and from every mutator that changes a contributing
     * field, so the stored hash can never drift from the row it describes.
     * Normalisation (trim, collapse whitespace, lower-case) happens inside
     * {@link Hashes#fingerprint}, so "Amazon  IN" and "amazon in" fingerprint alike.
     */
    private String computeDedupeHash() {
        return Hashes.fingerprint(userId, txnDate, type, Money.normalize(amount).toPlainString(),
                description, merchant);
    }

    /** Applies an edit and refreshes the derived fingerprint in one place. */
    public void applyEdit(TransactionType type, BigDecimal amount, LocalDate txnDate,
                          String description, String merchant, String notes, Category category) {
        this.type = type;
        this.amount = Money.normalize(amount);
        this.txnDate = txnDate;
        this.description = description;
        this.merchant = merchant;
        this.notes = notes;
        this.category = category;
        this.dedupeHash = computeDedupeHash();
    }

    /** Signed contribution to net cash flow: {@code +amount} for income, {@code -amount} for expense. */
    public BigDecimal signedAmount() {
        return type == TransactionType.INCOME ? amount : amount.negate();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public Long getImportJobId() {
        return importJobId;
    }

    public TransactionType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public LocalDate getTxnDate() {
        return txnDate;
    }

    public String getDescription() {
        return description;
    }

    public String getMerchant() {
        return merchant;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public TransactionSource getSource() {
        return source;
    }

    public String getExternalRef() {
        return externalRef;
    }

    public String getDedupeHash() {
        return dedupeHash;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Transaction transaction)) {
            return false;
        }
        return id != null && id.equals(transaction.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(Transaction.class);
    }

    /** Includes the amount but not the notes, which may hold free-text personal detail. */
    @Override
    public String toString() {
        return "Transaction{id=%d, userId=%d, type=%s, amount=%s, date=%s, source=%s}"
                .formatted(id, userId, type, amount, txnDate, source);
    }
}
