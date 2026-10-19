package libcore

import (
	"os"
	"sync"
	"testing"
	"time"
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

// Sleep and Wake on an instance that never started or was closed must do nothing.
func TestSleepWakeOutsideRunningState(t *testing.T) {
	b := &BoxInstance{}
	b.Sleep()
	b.Wake()
	_ = b.Close()
	b.Sleep()
	b.Wake()
}

// Close waits for a running user of the instance, and no new use starts once Close has begun.
func TestCloseWaitsForUsers(t *testing.T) {
	b := &BoxInstance{state: 1}
	if !b.acquire() {
		t.Fatal("acquire failed on a running instance")
	}
	closed := make(chan struct{})
	go func() {
		_ = b.Close()
		close(closed)
	}()
	select {
	case <-closed:
		t.Fatal("Close did not wait for the running user")
	case <-time.After(200 * time.Millisecond):
	}
	if b.acquire() {
		t.Fatal("acquire succeeded while closing")
	}
	b.release()
	select {
	case <-closed:
	case <-time.After(time.Second):
		t.Fatal("Close did not finish after the user released")
	}
	if b.acquire() {
		t.Fatal("acquire succeeded after Close")
	}
}

// A user that never ends does not block Close beyond closeWaitTimeout.
func TestCloseWaitIsBounded(t *testing.T) {
	old := closeWaitTimeout
	closeWaitTimeout = 100 * time.Millisecond
	defer func() { closeWaitTimeout = old }()

	b := &BoxInstance{state: 1}
	b.acquire()
	start := time.Now()
	_ = b.Close()
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Fatalf("Close blocked for %v", elapsed)
	}
	// the late release must not panic on the closed channel
	b.release()
}

// Many users and a Close at the same time: run with -race.
func TestConcurrentUsersAndClose(t *testing.T) {
	for round := 0; round < 50; round++ {
		b := &BoxInstance{state: 1}
		var wg sync.WaitGroup
		for g := 0; g < 8; g++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				for i := 0; i < 50; i++ {
					if b.acquire() {
						b.release()
					}
				}
			}()
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			_ = b.Close()
		}()
		wg.Wait()
		if b.acquire() {
			t.Fatal("acquire succeeded after Close")
		}
	}
}
