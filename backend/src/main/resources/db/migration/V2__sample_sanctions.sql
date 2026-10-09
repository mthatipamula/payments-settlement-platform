-- SAMPLE sanctions entries (fictional) so screening can be demonstrated. In production, load the U.S. Treasury
-- OFAC SDN and Consolidated lists (https://ofac.treasury.gov/sanctions-list-service) on a daily schedule.
INSERT INTO sanctions_entries (name, program, source) VALUES
 ('Ivan Drakovich',                 'SAMPLE-CYBER',  'SAMPLE'),
 ('Northwind Shadow Trading LLC',   'SAMPLE-SDGT',   'SAMPLE'),
 ('Crimson Harbor Logistics',       'SAMPLE-IRAN',   'SAMPLE'),
 ('Viktor Malenkov',                'SAMPLE-RUSSIA', 'SAMPLE'),
 ('Golden Crescent Exchange',       'SAMPLE-NARCO',  'SAMPLE'),
 ('Azure Dragon Holdings Limited',  'SAMPLE-DPRK',   'SAMPLE');
