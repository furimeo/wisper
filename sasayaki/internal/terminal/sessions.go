package terminal

import (
	"fmt"
	"sync"
)

// The set of sessions attached right now.
//
// Two jobs, both of which are about a frame reaching the wrong shell or a node falling
// over, and neither of which the panel can do for us.
//
// A session id is minted by the panel and appears on every frame precisely so that a stray
// frame from an abandoned session is dropped rather than typed into somebody else's shell
// (terminal.proto, TerminalFrame.session_id). That guarantee is only worth anything if an
// id identifies one session: two streams claiming the same id would make "drop the frame
// that does not match" a coin toss. So the second claim is refused.
//
// And the count is the node's ceiling. Every session holds a hijacked connection, three
// goroutines and a bounded output queue; the panel decides who is allowed to open a
// terminal, but nothing on the panel's side stops a browser from opening five hundred, and
// a node that agreed would fail in a way that took the customer's workloads with it.

type sessions struct {
	sync.Mutex
	limit int
	ids   map[string]struct{}
}

// claim registers a session id and returns the function that gives it back.
//
// The release function is idempotent and is always safe to defer: a session that failed to
// attach still has to give up its slot, and it is the same line of code either way.
func (s *sessions) claim(sessionID string) (func(), error) {
	s.Lock()
	defer s.Unlock()

	if s.ids == nil {
		s.ids = make(map[string]struct{})
	}
	if _, taken := s.ids[sessionID]; taken {
		return nil, fmt.Errorf("terminal: session %s is already attached on this node, so this "+
			"stream is a duplicate and its frames could not be told apart from the live one's",
			sessionID)
	}
	if len(s.ids) >= s.limit {
		return nil, fmt.Errorf("terminal: this node already has %d terminals open, which is its "+
			"limit; close one and open this session again", s.limit)
	}
	s.ids[sessionID] = struct{}{}

	released := false
	return func() {
		s.Lock()
		defer s.Unlock()
		if released {
			return
		}
		released = true
		delete(s.ids, sessionID)
	}, nil
}

// count is how many sessions are attached. Used by the tests that prove the ceiling holds
// and that a finished session gives its slot back.
func (s *sessions) count() int {
	s.Lock()
	defer s.Unlock()
	return len(s.ids)
}
