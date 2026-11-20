-- Hide the dataset page under Data Assets. Keep the record enabled so admins can restore visibility from menu management.
UPDATE menus SET visible=FALSE WHERE component='assets/datasets';
