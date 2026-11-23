// /models cache (P1): `devin models list` spawns a multi-second CLI process,
// so the gateway warms a slimmed snapshot at startup and refreshes it on a
// ticker — the endpoint only ever serves memory. A failed refresh keeps the
// last good list; the very first fetch failing is the only 502 left.
package main

import (
	"context"
	"encoding/json"
	"log"
	"sync"
	"time"
)

const (
	// modelsRefreshInterval is the staleness budget — model lineups change
	// rarely, and the list is only the picker's fallback data.
	modelsRefreshInterval = 6 * time.Hour
	// modelsFetchTimeout bounds one `devin models list` run.
	modelsFetchTimeout = 60 * time.Second
)

// modelsCache is the slimmed [{id,name}] JSON array (nil until the first
// successful fetch). ~3KB — devin's raw family-grouped output is kept out
// of memory entirely.
var modelsCache struct {
	mu  sync.RWMutex
	raw []byte
}

// modelsRefreshMu single-flights fetches: warmup, ticker and cold-miss
// requests never run two `devin models list` side by side.
var modelsRefreshMu sync.Mutex

// startModelsCache warms the snapshot off the critical path, then refreshes
// it every modelsRefreshInterval. Run as a goroutine from main.
func startModelsCache() {
	if err := fetchModels(); err != nil {
		log.Printf("models warmup: %v (cold requests retry on demand)", err)
	}
	for range time.Tick(modelsRefreshInterval) {
		if err := fetchModels(); err != nil {
			log.Printf("models refresh: %v (keeping last good)", err)
		}
	}
}

// cachedModels returns the snapshot, or nil before the first success.
func cachedModels() []byte {
	modelsCache.mu.RLock()
	defer modelsCache.mu.RUnlock()
	return modelsCache.raw
}

// modelsOrFetch returns the snapshot, joining the single-flight refresh on
// a cold cache: the first request after a failed warmup waits for one CLI
// run instead of erroring — same observable behaviour as pre-cache.
func modelsOrFetch() ([]byte, error) {
	if raw := cachedModels(); raw != nil {
		return raw, nil
	}
	modelsRefreshMu.Lock()
	defer modelsRefreshMu.Unlock()
	if modelsCache.raw != nil {
		return modelsCache.raw, nil // filled while we waited
	}
	if err := fetchModelsLocked(); err != nil {
		return nil, err
	}
	return modelsCache.raw, nil
}

// fetchModels runs one refresh under the single-flight mutex; the caller
// decides what an error means (warmup/ticker log it, keeping last good).
func fetchModels() error {
	modelsRefreshMu.Lock()
	defer modelsRefreshMu.Unlock()
	return fetchModelsLocked()
}

func fetchModelsLocked() error {
	ctx, cancel := context.WithTimeout(context.Background(), modelsFetchTimeout)
	defer cancel()
	out, err := runDevinOutput(ctx, *defaultDir, "models", "list", "--format", "json")
	if err != nil {
		return err
	}
	slim, err := slimModels(out)
	if err != nil {
		return err
	}
	modelsCache.mu.Lock()
	modelsCache.raw = slim
	modelsCache.mu.Unlock()
	return nil
}

// slimModels folds devin's family-grouped `models list --format json` down
// to [{"id","name"}]. The layout isn't a stable schema, so this mirrors the
// app's extractModels walk: any object carrying a string id (modelId/
// model_id/id/slug) plus a string name (name/title/label) is a leaf; every
// other object/array is descended. First occurrence wins on id duplicates.
func slimModels(raw []byte) ([]byte, error) {
	var root any
	if err := json.Unmarshal(raw, &root); err != nil {
		return nil, err
	}
	var out []map[string]string
	seen := map[string]bool{}
	var walk func(v any)
	walk = func(v any) {
		switch t := v.(type) {
		case map[string]any:
			id := firstString(t, "modelId", "model_id", "id", "slug")
			name := firstString(t, "name", "title", "label")
			if id != "" && name != "" {
				if !seen[id] {
					seen[id] = true
					out = append(out, map[string]string{"id": id, "name": name})
				}
				return
			}
			for _, c := range t {
				walk(c)
			}
		case []any:
			for _, c := range t {
				walk(c)
			}
		}
	}
	walk(root)
	if out == nil {
		out = []map[string]string{}
	}
	return json.Marshal(out)
}

func firstString(m map[string]any, keys ...string) string {
	for _, k := range keys {
		if s, ok := m[k].(string); ok && s != "" {
			return s
		}
	}
	return ""
}
