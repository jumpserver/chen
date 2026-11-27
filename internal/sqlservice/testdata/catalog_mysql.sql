-- Run only in a fresh disposable MySQL instance with lower_case_table_names=0.
CREATE DATABASE chen_qa;
CREATE DATABASE chen_qb;
CREATE DATABASE chen_hidden;
CREATE DATABASE chen_qa_aa;
CREATE DATABASE chen_qa_ab;
CREATE TABLE chen_qa.Alpha (id INT, label VARCHAR(20));
CREATE TABLE chen_qa.alpha (id INT);
CREATE TABLE chen_qa.hidden (id INT);
CREATE VIEW chen_qa.v_report AS SELECT id FROM chen_qa.Alpha;
CREATE USER 'catalog_reader'@'%' IDENTIFIED BY 'local-fixture-only';
GRANT SELECT ON chen_qa.Alpha TO 'catalog_reader'@'%';
GRANT SELECT ON chen_qa.v_report TO 'catalog_reader'@'%';
GRANT SELECT ON chen_qb.* TO 'catalog_reader'@'%';
GRANT SELECT ON chen_qa_aa.* TO 'catalog_reader'@'%';
GRANT SELECT ON chen_qa_ab.* TO 'catalog_reader'@'%';
