// Command mcchat-cli is a minimal terminal client for testing mcchat-server
// without Minecraft. Each stdin line is sent as a chat message.
//
//	mcchat-cli -url ws://127.0.0.1:25566/ws -token mcc_... -channel mc.daruks.com
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"time"

	"github.com/coder/websocket"
)

func main() {
	url := flag.String("url", "ws://127.0.0.1:25566/ws", "server WebSocket URL")
	token := flag.String("token", os.Getenv("MCCHAT_TOKEN"), "bearer token (or MCCHAT_TOKEN)")
	channel := flag.String("channel", "mc.daruks.com", "Minecraft server address to join")
	flag.Parse()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()

	dctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	c, resp, err := websocket.Dial(dctx, *url, &websocket.DialOptions{
		HTTPHeader: http.Header{"Authorization": {"Bearer " + *token}},
	})
	cancel()
	if err != nil {
		if resp != nil {
			fmt.Fprintf(os.Stderr, "dial: HTTP %d\n", resp.StatusCode)
		}
		fmt.Fprintln(os.Stderr, "dial:", err)
		os.Exit(1)
	}
	defer c.CloseNow()

	send := func(v any) {
		b, _ := json.Marshal(v)
		if err := c.Write(ctx, websocket.MessageText, b); err != nil {
			fmt.Fprintln(os.Stderr, "write:", err)
		}
	}
	send(map[string]string{"type": "join", "channel": *channel})

	go func() {
		sc := bufio.NewScanner(os.Stdin)
		for sc.Scan() {
			send(map[string]string{"type": "send", "text": sc.Text()})
		}
		if err := sc.Err(); err != nil {
			fmt.Fprintln(os.Stderr, "stdin:", err)
		}
		stop()
	}()

	for {
		_, b, err := c.Read(ctx)
		if err != nil {
			if ctx.Err() == nil {
				fmt.Fprintln(os.Stderr, "read:", err)
			}
			c.Close(websocket.StatusNormalClosure, "")
			return
		}
		printFrame(b)
	}
}

type msg struct {
	ID      int64  `json:"id"`
	Channel string `json:"channel"`
	Name    string `json:"name"`
	Text    string `json:"text"`
	TS      int64  `json:"ts"`
}

func printFrame(b []byte) {
	var f struct {
		Type     string `json:"type"`
		Name     string `json:"name"`
		Code     string `json:"code"`
		Channel  string `json:"channel"`
		Messages []msg  `json:"messages"`
	}
	if err := json.Unmarshal(b, &f); err != nil {
		fmt.Printf("?? %s\n", b)
		return
	}
	line := func(m msg) {
		fmt.Printf("%s <%s> %s\n", time.UnixMilli(m.TS).Format("15:04:05"), m.Name, m.Text)
	}
	switch f.Type {
	case "hello":
		fmt.Printf("* connected as %s\n", f.Name)
	case "history":
		fmt.Printf("* joined %s (%d messages)\n", f.Channel, len(f.Messages))
		for _, m := range f.Messages {
			line(m)
		}
	case "msg":
		var m msg
		json.Unmarshal(b, &m)
		line(m)
	case "error":
		fmt.Printf("! error: %s\n", f.Code)
	default:
		fmt.Printf("?? %s\n", b)
	}
}
