CREATE TABLE profile_observation (
 username VARCHAR(39) NOT NULL,
 observation_day DATE NOT NULL,
 observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
 score INTEGER NOT NULL CHECK (score BETWEEN 0 AND 100),
 stars BIGINT NOT NULL,
 repositories INTEGER NOT NULL,
 events INTEGER NOT NULL,
 languages_json VARCHAR(8192) NOT NULL,
 repository_ids_json VARCHAR(8192) NOT NULL,
 truncated BOOLEAN NOT NULL,
 PRIMARY KEY (username, observation_day)
);
CREATE INDEX observation_date_idx ON profile_observation(observed_at);
