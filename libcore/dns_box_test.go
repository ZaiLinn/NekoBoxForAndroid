package libcore

import (
	"context"
	"errors"
	"syscall"
	"testing"

	mDNS "github.com/miekg/dns"
)

type testLocalDNS struct{ handle int64 }

func (d testLocalDNS) Raw() bool                                     { return true }
func (d testLocalDNS) NetworkHandle() int64                          { return d.handle }
func (d testLocalDNS) Lookup(*ExchangeContext, string, string) error { return nil }
func (d testLocalDNS) Exchange(*ExchangeContext, []byte) error       { return nil }

func TestRawDNSRequiresUnderlyingNetwork(t *testing.T) {
	previous := rawQueryFunc
	defer func() { rawQueryFunc = previous }()
	called := false
	rawQueryFunc = func(int64, []byte) ([]byte, error) {
		called = true
		return nil, nil
	}

	transport := &platformLocalDNSTransport{iif: testLocalDNS{}, raw: true}
	question := new(mDNS.Msg)
	question.SetQuestion("example.org.", mDNS.TypeA)
	_, err := transport.Exchange(context.Background(), question)
	if !errors.Is(err, syscall.ENETUNREACH) {
		t.Fatalf("expected ENETUNREACH without an underlying network, got %v", err)
	}
	if called {
		t.Fatal("raw DNS query was sent without an underlying network")
	}
}
