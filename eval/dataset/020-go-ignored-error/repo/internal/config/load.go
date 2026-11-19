package config

import (
	"encoding/json"
	"fmt"
	"os"
	"time"
)

type Config struct {
	ListenAddr   string        `json:"listen_addr"`
	DatabaseURL  string        `json:"database_url"`
	ReadTimeout  time.Duration `json:"read_timeout"`
	WriteTimeout time.Duration `json:"write_timeout"`
}

// Load reads and decodes the JSON configuration file at path.
func Load(path string) (*Config, error) {
	f, _ := os.Open(path)
	defer f.Close()

	var cfg Config
	if err := json.NewDecoder(f).Decode(&cfg); err != nil {
		return nil, fmt.Errorf("decode %s: %w", path, err)
	}
	if cfg.ListenAddr == "" {
		cfg.ListenAddr = ":8080"
	}
	return &cfg, nil
}
