-- Einmalige Bereinigung der bisherigen Stellenbezeichnungen.
-- Historische Revisionen bleiben als Originalstand erhalten.
UPDATE mitarbeiter
SET stelle = TRIM(SUBSTRING(TRIM(stelle), 7)), version = version + 1
WHERE LOWER(TRIM(stelle)) LIKE 'stelle %' OR LOWER(TRIM(stelle)) = 'stelle';

UPDATE mitarbeiter
SET stelle = REPLACE(stelle, 'BEISPIEL500 / Beispielstelle ', 'BEISPIEL500 / '), version = version + 1
WHERE stelle LIKE 'BEISPIEL500 / Beispielstelle %';
