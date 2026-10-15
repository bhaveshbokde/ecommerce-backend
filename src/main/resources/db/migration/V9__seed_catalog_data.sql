-- V9: catalogue seed data.
--
-- WHAT IS AND IS NOT SEEDED HERE
-- Categories and products only. These are *reference* data: every environment
-- benefits from the same starting catalogue, and Flyway records that it was
-- applied, so the seed is reproducible and versioned.
--
-- NO USERS ARE CREATED HERE — deliberately. A migration that inserts an account
-- with a known password would put a working credential into every environment
-- that ever ran it, including any that later became public. The development
-- administrator is instead created at startup by AdminBootstrap, only when
-- ECOMMERCE_ADMIN_EMAIL and ECOMMERCE_ADMIN_PASSWORD are both present in the
-- environment, and never under the `prod` profile.
--
-- Idempotent (ON CONFLICT DO NOTHING) so re-running against a database where an
-- operator already inserted a matching row is harmless.

INSERT INTO categories (name, slug, description) VALUES
    ('Electronics',  'electronics',  'Phones, laptops, audio and accessories'),
    ('Books',        'books',        'Paperbacks, hardbacks and technical titles'),
    ('Home & Kitchen','home-kitchen','Appliances, cookware and storage'),
    ('Sports',       'sports',       'Fitness equipment and outdoor gear'),
    ('Clothing',     'clothing',     'Apparel and footwear')
ON CONFLICT DO NOTHING;

INSERT INTO products (sku, name, description, price, category_id, active)
SELECT v.sku, v.name, v.description, v.price, c.id, TRUE
FROM (VALUES
    ('ELEC-LAPTOP-001',  'UltraBook 14 Pro',        'Lightweight 14-inch laptop, 16 GB RAM, 512 GB SSD', 89999.0000, 'electronics'),
    ('ELEC-PHONE-001',   'Nova 5G Smartphone',      '6.5-inch display, 128 GB storage, dual camera', 24999.0000, 'electronics'),
    ('ELEC-HPHONE-001',  'AcoustiMax Headphones',   'Over-ear, active noise cancelling, 30 h battery', 7499.0000, 'electronics'),
    ('ELEC-WATCH-001',   'PulseFit Smartwatch',     'Heart-rate and SpO2 tracking, 7-day battery',    4999.0000, 'electronics'),
    ('ELEC-KBD-001',     'TactileOne Keyboard',     'Mechanical, hot-swappable switches, backlit',    6499.0000, 'electronics'),

    ('BOOK-TECH-001',    'Designing Data-Intensive Applications', 'Kleppmann — distributed systems and data', 1899.0000, 'books'),
    ('BOOK-TECH-002',    'Effective Java',          'Bloch — idiomatic Java, third edition',           1499.0000, 'books'),
    ('BOOK-TECH-003',    'SQL Performance Explained','Winand — indexing and query tuning',             1299.0000, 'books'),
    ('BOOK-FICT-001',    'The Silent Harbour',       'Literary fiction, 384 pages',                      599.0000, 'books'),

    ('HOME-BLEND-001',   'VortexBlend 600',         '600 W blender with glass jar',                    3499.0000, 'home-kitchen'),
    ('HOME-KETTLE-001',  'RapidBoil Kettle',        '1.7 L stainless steel electric kettle',           1799.0000, 'home-kitchen'),
    ('HOME-PAN-001',     'ChefGrade Frying Pan',    '28 cm non-stick, induction compatible',           2299.0000, 'home-kitchen'),

    ('SPRT-MAT-001',     'GripFlow Yoga Mat',       '6 mm non-slip, with carry strap',                 1299.0000, 'sports'),
    ('SPRT-DUMB-001',    'IronCore Dumbbell Set',   'Adjustable 2-20 kg pair',                         8999.0000, 'sports'),
    ('SPRT-BOTL-001',    'HydroTrail Bottle',       '1 L insulated stainless steel',                    999.0000, 'sports'),

    ('CLTH-TSHIRT-001',  'Everyday Cotton T-Shirt', '100% combed cotton, regular fit',                  899.0000, 'clothing'),
    ('CLTH-JACKET-001',  'StormShell Jacket',       'Water-resistant shell with taped seams',          4499.0000, 'clothing'),
    ('CLTH-SHOES-001',   'TrailRunner Shoes',       'Cushioned trail running shoe',                    5299.0000, 'clothing')
) AS v(sku, name, description, price, category_slug)
JOIN categories c ON c.slug = v.category_slug
ON CONFLICT DO NOTHING;

-- Every product needs an inventory row; without one it can never be sold.
-- Varied quantities, including a deliberately scarce item used by the
-- concurrency tests to prove overselling is impossible.
INSERT INTO inventory (product_id, available, reserved)
SELECT p.id,
       CASE
           WHEN p.sku = 'ELEC-LAPTOP-001' THEN 5      -- scarce, for contention tests
           WHEN p.sku LIKE 'ELEC-%'       THEN 50
           WHEN p.sku LIKE 'BOOK-%'       THEN 200
           WHEN p.sku LIKE 'HOME-%'       THEN 75
           WHEN p.sku LIKE 'SPRT-%'       THEN 120
           ELSE 150
       END,
       0
FROM products p
ON CONFLICT DO NOTHING;
