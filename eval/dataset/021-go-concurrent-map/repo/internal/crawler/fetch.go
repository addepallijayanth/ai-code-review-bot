package crawler

import (
	"io"
	"net/http"
	"sync"
	"time"
)

var client = &http.Client{Timeout: 10 * time.Second}

// FetchAll returns the body size of every URL that could be fetched.
func FetchAll(urls []string) map[string]int {
	sizes := make(map[string]int)
	var wg sync.WaitGroup
	for _, u := range urls {
		wg.Add(1)
		go func(url string) {
			defer wg.Done()
			resp, err := client.Get(url)
			if err != nil {
				return
			}
			defer resp.Body.Close()
			body, err := io.ReadAll(resp.Body)
			if err != nil {
				return
			}
			sizes[url] = len(body)
		}(u)
	}
	wg.Wait()
	return sizes
}
