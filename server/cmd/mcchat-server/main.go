// Command mcchat-server is the chat backend for the mcchat Minecraft mod.
//
//	mcchat-server serve           [-config path]
//	mcchat-server token add       [-config path] <name>
//	mcchat-server token list      [-config path]
//	mcchat-server token revoke    [-config path] <id>
//	mcchat-server keygen
package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"text/tabwriter"
	"time"

	"mcchat/internal/auth"
	"mcchat/internal/config"
	"mcchat/internal/sanitize"
	"mcchat/internal/store"
	"mcchat/internal/ws"
)

const usage = `usage:
  mcchat-server serve        [-config path]
  mcchat-server token add    [-config path] [-hash hex] <name>
  mcchat-server token gen
  mcchat-server token list   [-config path]
  mcchat-server token revoke [-config path] <id>
  mcchat-server keygen

environment:
  MCCHAT_KEY       base64 32-byte AES key for message bodies (required for serve)
  MCCHAT_KEY_ID    id of MCCHAT_KEY, 1-255 (default 1)
  MCCHAT_OLD_KEYS  "id:base64,..." old keys kept for decryption
`

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func run(args []string) error {
	if len(args) == 0 {
		fmt.Fprint(os.Stderr, usage)
		os.Exit(2)
	}
	switch args[0] {
	case "serve":
		return serve(args[1:])
	case "token":
		if len(args) < 2 {
			break
		}
		switch args[1] {
		case "add":
			return tokenAdd(args[2:])
		case "gen":
			return tokenGen()
		case "list":
			return tokenList(args[2:])
		case "revoke":
			return tokenRevoke(args[2:])
		}
	case "keygen":
		fmt.Println(store.NewKey())
		return nil
	}
	fmt.Fprint(os.Stderr, usage)
	os.Exit(2)
	return nil
}

// parse handles the shared -config flag and returns the config and the
// remaining positional arguments. extra may register more flags.
func parse(name string, args []string, extra ...func(*flag.FlagSet)) (config.Config, []string, error) {
	fs := flag.NewFlagSet(name, flag.ExitOnError)
	path := fs.String("config", "config.json", "config file (missing file = defaults)")
	for _, f := range extra {
		f(fs)
	}
	fs.Parse(args)
	cfg, err := config.Load(*path)
	return cfg, fs.Args(), err
}

func serve(args []string) error {
	cfg, _, err := parse("serve", args)
	if err != nil {
		return err
	}
	cipher, err := store.CipherFromEnv()
	if err != nil {
		return err
	}
	st, err := store.Open(cfg.DB, cipher)
	if err != nil {
		return err
	}
	defer st.Close()

	log := slog.New(slog.NewTextHandler(os.Stderr, nil))
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	srv := &http.Server{
		Addr:              cfg.Listen,
		Handler:           ws.New(ctx, cfg, st, log).Handler(),
		ReadHeaderTimeout: 10 * time.Second,
	}
	errc := make(chan error, 1)
	go func() { errc <- srv.ListenAndServe() }()
	log.Info("listening", "addr", cfg.Listen, "db", cfg.DB)

	select {
	case err := <-errc:
		return err
	case <-ctx.Done():
	}
	log.Info("shutting down")
	sctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.Shutdown(sctx); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}

func openTokens(args []string, name string, extra ...func(*flag.FlagSet)) (*store.Store, []string, error) {
	cfg, rest, err := parse(name, args, extra...)
	if err != nil {
		return nil, nil, err
	}
	st, err := store.Open(cfg.DB, nil)
	return st, rest, err
}

// tokenAdd issues a token. With -hash, it registers a token generated
// elsewhere (token gen) so the token itself never touches this host.
func tokenAdd(args []string) error {
	var hashHex string
	st, rest, err := openTokens(args, "token add", func(fs *flag.FlagSet) {
		fs.StringVar(&hashHex, "hash", "", "register this SHA-256 hex (from `token gen`) instead of generating a token")
	})
	if err != nil {
		return err
	}
	defer st.Close()
	if len(rest) != 1 {
		return errors.New("token add takes exactly one <name>")
	}
	name := sanitize.Text(rest[0], 16)
	if name == "" || name != rest[0] {
		return fmt.Errorf("invalid name %q (max 16 chars, no control/§)", rest[0])
	}

	var tok string
	var hash []byte
	if hashHex != "" {
		hash, err = hex.DecodeString(hashHex)
		if err != nil || len(hash) != sha256.Size {
			return errors.New("-hash must be 64 hex characters")
		}
	} else {
		tok, hash = auth.NewToken()
	}
	id, err := st.AddToken(context.Background(), name, hash)
	if err != nil {
		return err
	}
	if tok == "" {
		fmt.Fprintf(os.Stderr, "registered token #%d for %q\n", id, name)
		return nil
	}
	fmt.Fprintf(os.Stderr, "created token #%d for %q. It is shown only once:\n", id, name)
	fmt.Println(tok)
	return nil
}

// tokenGen prints a new token and its hash without touching any database.
func tokenGen() error {
	tok, hash := auth.NewToken()
	fmt.Printf("token: %s\nhash:  %s\n", tok, hex.EncodeToString(hash))
	return nil
}

func tokenList(args []string) error {
	st, _, err := openTokens(args, "token list")
	if err != nil {
		return err
	}
	defer st.Close()
	toks, err := st.ListTokens(context.Background())
	if err != nil {
		return err
	}
	w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
	fmt.Fprintln(w, "ID\tNAME\tCREATED\tSTATUS")
	for _, t := range toks {
		status := "active"
		if t.RevokedAt != nil {
			status = "revoked " + t.RevokedAt.Format(time.DateTime)
		}
		fmt.Fprintf(w, "%d\t%s\t%s\t%s\n", t.ID, t.Name, t.CreatedAt.Format(time.DateTime), status)
	}
	return w.Flush()
}

func tokenRevoke(args []string) error {
	st, rest, err := openTokens(args, "token revoke")
	if err != nil {
		return err
	}
	defer st.Close()
	if len(rest) != 1 {
		return errors.New("token revoke takes exactly one <id>")
	}
	id, err := strconv.ParseInt(rest[0], 10, 64)
	if err != nil {
		return fmt.Errorf("invalid id %q", rest[0])
	}
	if err := st.RevokeToken(context.Background(), id); err != nil {
		return err
	}
	fmt.Fprintf(os.Stderr, "revoked token #%d\n", id)
	return nil
}
