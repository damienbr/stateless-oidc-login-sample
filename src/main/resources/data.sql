-- FICTITIOUS TEST DATA. Email addresses use example.com, a domain reserved for documentation.
MERGE INTO customer (customer_id, email, display_name, active) KEY (customer_id) VALUES
    ('C-1001', 'alex.martin@example.com',  'Alex Martin',  TRUE),    -- itsme already linked (see below)
    ('C-1002', 'sam.peeters@example.com',  'Sam Peeters',  TRUE),    -- itsme not linked: the itsme login is refused
    ('C-1003', 'robin.dubois@example.com', 'Robin Dubois', FALSE);   -- contract closed, itsme linked earlier

MERGE INTO itsme_link (customer_id, itsme_sub, linked_at, last_login_at) KEY (customer_id) VALUES
    ('C-1001', 'stub-sub-alex',  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('C-1003', 'stub-sub-robin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
