INSERT INTO cfg.option_value (option_set_code, state_code, value_code, label, sort_order, active)
VALUES
  ('PROPERTY_TYPE', '*', 'COMMERCIAL', 'Commercial', 6, TRUE),
  ('PROPERTY_TYPE', '*', 'INDUSTRIAL', 'Industrial', 7, TRUE),
  ('PROPERTY_TYPE', '*', 'PLOT_SITE', 'Plot / Site', 8, TRUE)
ON CONFLICT (option_set_code, state_code, value_code)
DO UPDATE SET label = EXCLUDED.label, sort_order = EXCLUDED.sort_order, active = TRUE;