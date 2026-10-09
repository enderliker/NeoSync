CREATE TABLE instances (
	id TEXT NOT NULL,
	path TEXT NOT NULL,
	applied_content_set_id TEXT NULL,

	install_stage TEXT NOT NULL,
	launcher_feature_version TEXT NOT NULL,
	update_channel TEXT NOT NULL DEFAULT 'release',

	name TEXT NOT NULL,
	icon_path TEXT NULL,

	created INTEGER NOT NULL,
	modified INTEGER NOT NULL,
	last_played INTEGER NULL,

	submitted_time_played INTEGER NOT NULL DEFAULT 0,
	recent_time_played INTEGER NOT NULL DEFAULT 0,

	PRIMARY KEY (id),
	UNIQUE (path)
);
CREATE TABLE instance_content_sets (
	id TEXT NOT NULL,
	instance_id TEXT NOT NULL,

	name TEXT NOT NULL,
	source_kind TEXT NOT NULL,
	status TEXT NOT NULL,

	game_version TEXT NOT NULL,
	protocol_version INTEGER NULL,
	loader TEXT NOT NULL,
	loader_version TEXT NULL,

	created INTEGER NOT NULL,
	modified INTEGER NOT NULL,

	PRIMARY KEY (id),
	FOREIGN KEY (instance_id) REFERENCES instances(id) ON DELETE CASCADE
);
CREATE TABLE instance_links (
	instance_id TEXT NOT NULL,
	link_kind TEXT NOT NULL,

	modrinth_project_id TEXT NULL,
	modrinth_version_id TEXT NULL,

	server_project_id TEXT NULL,

	content_project_id TEXT NULL,
	content_version_id TEXT NULL,

	hosting_server_id TEXT NULL,
	hosting_instance_ids JSONB NULL,
	hosting_active_instance_id TEXT NULL,

	shared_instance_id TEXT NULL, imported_name TEXT NULL, imported_version_number TEXT NULL, imported_filename TEXT NULL, shared_instance_role TEXT NULL, shared_instance_manager_id TEXT NULL, shared_instance_linked_user_id TEXT NULL, shared_instance_server_manager_name TEXT NULL, shared_instance_server_manager_icon_url TEXT NULL,

	PRIMARY KEY (instance_id),
	FOREIGN KEY (instance_id) REFERENCES instances(id) ON DELETE CASCADE
);
CREATE TABLE instance_launch_overrides (
	instance_id TEXT NOT NULL,
	overrides JSONB NOT NULL,

	PRIMARY KEY (instance_id),
	FOREIGN KEY (instance_id) REFERENCES instances(id) ON DELETE CASCADE
);
CREATE TABLE instance_sync_preferences (
	instance_id TEXT NOT NULL,
	feature TEXT NOT NULL,
	enabled INTEGER NOT NULL CHECK (enabled IN (0, 1)),
	PRIMARY KEY (instance_id, feature),
	FOREIGN KEY (instance_id) REFERENCES instances (id)
		ON DELETE CASCADE,
	FOREIGN KEY (feature) REFERENCES sync_feature_settings (feature)
		ON DELETE CASCADE
);
CREATE TABLE sync_feature_settings (
	feature TEXT PRIMARY KEY NOT NULL,
	globally_enabled INTEGER NOT NULL CHECK (globally_enabled IN (0, 1)),
	new_instance_default INTEGER NOT NULL CHECK (new_instance_default IN (0, 1))
);