-- Neue Versionen werden auch nach Löschen und Neuanlegen einer Regel nicht wiederverwendet.
-- Vorhandene Regeln und ihre Versionsstände bleiben unverändert.
CREATE SEQUENCE besetzungsregel_version START WITH 1;
ALTER SEQUENCE besetzungsregel_version RESTART WITH (SELECT COALESCE(MAX(version), 0) + 1 FROM besetzungsregel);
