-- Run only in a fresh disposable StarRocks 3.5 instance.
CREATE DATABASE chen_qa;
CREATE DATABASE chen_qb;
CREATE DATABASE chen_hidden;
CREATE DATABASE chen_qa_aa;
CREATE DATABASE chen_qa_ab;
CREATE TABLE chen_qa.Alpha (id INT, label VARCHAR(20))
DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num"="1");
CREATE TABLE chen_qa.alpha (id INT)
DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num"="1");
CREATE VIEW chen_qa.v_report AS SELECT id FROM chen_qa.Alpha;
CREATE USER catalog_reader IDENTIFIED BY 'local-fixture-only';
GRANT SELECT ON TABLE chen_qa.Alpha TO USER catalog_reader;
GRANT SELECT ON VIEW chen_qa.v_report TO USER catalog_reader;
GRANT SELECT ON ALL TABLES IN DATABASE chen_qb TO USER catalog_reader;
GRANT SELECT ON ALL TABLES IN DATABASE chen_qa_aa TO USER catalog_reader;
GRANT SELECT ON ALL TABLES IN DATABASE chen_qa_ab TO USER catalog_reader;
