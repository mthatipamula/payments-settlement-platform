-- Why a settlement batch failed, e.g. the reason in a FedACH file rejection.
ALTER TABLE ach_settlement_batches ADD COLUMN status_reason TEXT;
