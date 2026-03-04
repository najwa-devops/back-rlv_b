CREATE TABLE IF NOT EXISTS Comptes (
    numero VARCHAR(9) NOT NULL,
    libelle VARCHAR(255) NOT NULL,
    PRIMARY KEY (numero)
);

CREATE TABLE IF NOT EXISTS Cptjournal (
    Numero BIGINT NOT NULL,
    ndosjrn VARCHAR(50) NOT NULL,
    nmois INT NOT NULL,
    Mois VARCHAR(20) NOT NULL,
    ncompt VARCHAR(9) NOT NULL,
    ecriture VARCHAR(1000) NOT NULL,
    debit DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    credit DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    valider CHAR(1) NOT NULL DEFAULT '1',
    datcompl DATE NOT NULL,
    dat INT NOT NULL,
    annee INT NOT NULL,
    mnt_rester DECIMAL(15,2) NULL,
    INDEX idx_cptjournal_numero (Numero),
    INDEX idx_cptjournal_journal_mois (ndosjrn, nmois)
);

CREATE TABLE IF NOT EXISTS cptjournal_sync_tracker (
    statement_id BIGINT NOT NULL PRIMARY KEY,
    synced_at DATETIME NOT NULL
);
