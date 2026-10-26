package dm

import (
	"fmt"
	"sync"
	"testing"
)

func TestChenErrorTemplatesStayImmutableAcrossConnections(t *testing.T) {
	template := ECGO_COMMUNITION_ERROR
	var wg sync.WaitGroup
	for i := 0; i < 12; i++ {
		i := i
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 100; j++ {
				detail := fmt.Sprintf("connection-%d-operation-%d", i, j)
				err := template.addDetail(detail).throw()
				if err == template || err.detail != detail || len(err.stack) == 0 {
					t.Error("per-operation error data was shared")
				}
				if plain := template.throw(); plain == template || plain.detail != "" || len(plain.stack) == 0 {
					t.Error("plain error inherited another connection's detail")
				}
			}
		}()
	}
	wg.Wait()
	if template.detail != "" || template.stack != nil {
		t.Fatal("global error template was mutated")
	}
}
