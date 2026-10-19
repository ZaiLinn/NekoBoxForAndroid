package libcore

import (
	"os"
	"sync"
	"testing"
)

// Run with -race: replacing, closing and resetting the main instance from several goroutines
// must not race or panic.
func TestMainInstanceConcurrentAccess(t *testing.T) {
	// SetAsMain serves the protect socket in the working directory
	dir := t.TempDir()
	wd, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Chdir(dir); err != nil {
		t.Fatal(err)
	}
	defer os.Chdir(wd)

	var wg sync.WaitGroup
	for g := 0; g < 4; g++ {
		wg.Add(3)
		go func() {
			defer wg.Done()
			for i := 0; i < 200; i++ {
				b := &BoxInstance{}
				b.SetAsMain()
				_ = b.Close()
			}
		}()
		go func() {
			defer wg.Done()
			for i := 0; i < 200; i++ {
				ResetAllConnections(true)
			}
		}()
		go func() {
			defer wg.Done()
			for i := 0; i < 200; i++ {
				_ = getMainInstance()
			}
		}()
	}
	wg.Wait()

	b := &BoxInstance{}
	b.SetAsMain()
	if getMainInstance() != b {
		t.Fatal("SetAsMain did not set the main instance")
	}
	_ = b.Close()
	if getMainInstance() != nil {
		t.Fatal("Close did not clear the main instance")
	}
}
