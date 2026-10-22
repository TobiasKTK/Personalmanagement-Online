CREATE TABLE besetzungsregel (
    organisationseinheit_id BIGINT PRIMARY KEY REFERENCES organisationseinheit(id) ON DELETE CASCADE,
    mindestbesetzung DECIMAL(6,1) NOT NULL CHECK (mindestbesetzung >= 0),
    wochentage VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);
