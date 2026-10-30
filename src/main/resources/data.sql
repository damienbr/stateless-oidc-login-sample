-- FICTITIOUS TEST DATA. Email addresses use example.com, a domain reserved for documentation.
MERGE INTO customer (customer_id, email, display_name, active) KEY (customer_id) VALUES
    ('C-1001', 'alex.martin@example.com',  'Alex Martin',  TRUE),    -- itsme already linked (see below)
    ('C-1002', 'sam.peeters@example.com',  'Sam Peeters',  TRUE),    -- itsme not linked: the itsme login is refused
    ('C-1003', 'robin.dubois@example.com', 'Robin Dubois', FALSE);   -- contract closed, itsme linked earlier

MERGE INTO itsme_link (customer_id, itsme_sub, linked_at, last_login_at) KEY (customer_id) VALUES
    ('C-1001', 'stub-sub-alex',  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('C-1003', 'stub-sub-robin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- DEMO ONLY: one open order and one open offer per active customer.
MERGE INTO customer_order (order_id, customer_id, description, amount, status) KEY (order_id) VALUES
    ('O-2001', 'C-1001', 'Smart thermostat', 149.00, 'OPEN'),
    ('O-2002', 'C-1002', 'Smart thermostat', 149.00, 'OPEN');

MERGE INTO offer (offer_id, customer_id, description, price_per_kwh, status) KEY (offer_id) VALUES
    ('F-3001', 'C-1001', 'Fixed electricity price for 12 months', 0.2850, 'OPEN'),
    ('F-3002', 'C-1002', 'Fixed electricity price for 12 months', 0.2850, 'OPEN');
