-- V7: built-in system categories.
--
-- These are reference data, not user data, so they belong in a versioned migration
-- rather than in an application bootstrap routine: every environment then has
-- byte-identical seed data and Flyway records that it was applied.
--
-- Idempotent on purpose (ON CONFLICT DO NOTHING) so that re-running this statement
-- against a database where an operator already inserted a matching row is harmless.

INSERT INTO categories (user_id, name, type, color, system, archived)
VALUES
    (NULL, 'Salary',            'INCOME',  '#2E7D32', TRUE, FALSE),
    (NULL, 'Interest',          'INCOME',  '#388E3C', TRUE, FALSE),
    (NULL, 'Refunds',           'INCOME',  '#43A047', TRUE, FALSE),
    (NULL, 'Other Income',      'INCOME',  '#66BB6A', TRUE, FALSE),

    (NULL, 'Groceries',         'EXPENSE', '#EF6C00', TRUE, FALSE),
    (NULL, 'Dining',            'EXPENSE', '#F4511E', TRUE, FALSE),
    (NULL, 'Transport',         'EXPENSE', '#1E88E5', TRUE, FALSE),
    (NULL, 'Housing',           'EXPENSE', '#6D4C41', TRUE, FALSE),
    (NULL, 'Utilities',         'EXPENSE', '#00897B', TRUE, FALSE),
    (NULL, 'Healthcare',        'EXPENSE', '#D81B60', TRUE, FALSE),
    (NULL, 'Entertainment',     'EXPENSE', '#8E24AA', TRUE, FALSE),
    (NULL, 'Shopping',          'EXPENSE', '#C2185B', TRUE, FALSE),
    (NULL, 'Education',         'EXPENSE', '#3949AB', TRUE, FALSE),
    (NULL, 'Insurance',         'EXPENSE', '#5E35B1', TRUE, FALSE),
    (NULL, 'Travel',            'EXPENSE', '#00ACC1', TRUE, FALSE),
    (NULL, 'Subscriptions',     'EXPENSE', '#7CB342', TRUE, FALSE),
    (NULL, 'Fees & Charges',    'EXPENSE', '#757575', TRUE, FALSE),
    (NULL, 'Uncategorized',     'EXPENSE', '#9E9E9E', TRUE, FALSE)
ON CONFLICT DO NOTHING;
