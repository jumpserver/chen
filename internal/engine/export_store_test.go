//go:build linux || darwin || freebsd || openbsd || netbsd

package engine

import (
	"os"
	"path/filepath"
	"testing"
)

func TestExportStoreRecoversCrashWithoutDeletingLiveInstance(t *testing.T) {
	root := t.TempDir()
	live, liveLock, err := openExportStore(root)
	if err != nil {
		t.Fatal(err)
	}
	defer closeExportStore(live, liveLock)
	liveFile := filepath.Join(live, ".export-active")
	if err = os.WriteFile(liveFile, []byte("private"), 0600); err != nil {
		t.Fatal(err)
	}
	crashed, crashedLock, err := openExportStore(root)
	if err != nil {
		t.Fatal(err)
	}
	crashedFile := filepath.Join(crashed, ".export-abandoned")
	if err = os.WriteFile(crashedFile, []byte("private"), 0600); err != nil {
		t.Fatal(err)
	}
	_ = crashedLock.Close() // process exit releases OS locks without deleting its files
	newDir, newLock, err := openExportStore(root)
	if err != nil {
		t.Fatal(err)
	}
	defer closeExportStore(newDir, newLock)
	if _, err = os.Stat(crashed); !os.IsNotExist(err) {
		t.Fatal("crashed instance's private export was retained")
	}
	if _, err = os.Stat(liveFile); err != nil {
		t.Fatal("startup deleted another live instance's export")
	}
}
