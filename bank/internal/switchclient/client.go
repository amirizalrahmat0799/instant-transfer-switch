// Package switchclient is the bank's connection to the central switch.
package switchclient

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"time"

	"github.com/amirizalrahmat0799/instant-transfer-switch/bank/internal/iso"
)

var ErrNotFound = errors.New("not found")

type Client struct {
	BaseURL string
	BIC     string
	APIKey  string
	HTTP    *http.Client
}

func New(baseURL, bic, apiKey string) *Client {
	// A little longer than the switch's own timeout to the creditor bank, so the switch always answers first.
	return &Client{BaseURL: baseURL, BIC: bic, APIKey: apiKey, HTTP: &http.Client{Timeout: 12 * time.Second}}
}

// Resolution is the switch's answer to a proxy lookup (a DuitNow-style "pay to phone number").
type Resolution struct {
	BIC           string `json:"bic"`
	BankName      string `json:"bankName"`
	AccountNumber string `json:"accountNumber"`
	MaskedName    string `json:"maskedName"`
	AccountName   string `json:"accountName"`
}

type ProxyRegistration struct {
	Type          string `json:"type"`
	Value         string `json:"value"`
	AccountNumber string `json:"accountNumber"`
	AccountName   string `json:"accountName"`
}

type CycleTransaction struct {
	EndToEndID string `json:"endToEndId"`
	Direction  string `json:"direction"` // SENT or RECEIVED, from this bank's point of view
	Amount     int64  `json:"amount"`
}

func (c *Client) do(ctx context.Context, method, path, contentType string, body []byte) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, method, c.BaseURL+path, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	req.Header.Set("X-Participant", c.BIC)
	req.Header.Set("X-Api-Key", c.APIKey)
	return c.HTTP.Do(req)
}

func readError(res *http.Response) error {
	b, _ := io.ReadAll(io.LimitReader(res.Body, 4096))
	var problem struct {
		Detail string `json:"detail"`
	}
	if json.Unmarshal(b, &problem) == nil && problem.Detail != "" {
		return fmt.Errorf("switch: %s (%d)", problem.Detail, res.StatusCode)
	}
	return fmt.Errorf("switch returned %d", res.StatusCode)
}

func (c *Client) RegisterProxy(ctx context.Context, p ProxyRegistration) error {
	body, _ := json.Marshal(p)
	res, err := c.do(ctx, http.MethodPost, "/api/v1/proxies", "application/json", body)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	if res.StatusCode >= 300 {
		return readError(res)
	}
	return nil
}

func (c *Client) Lookup(ctx context.Context, proxyType, value string) (Resolution, error) {
	var r Resolution
	res, err := c.do(ctx, http.MethodGet, "/api/v1/proxies/"+url.PathEscape(proxyType)+"/"+url.PathEscape(value), "", nil)
	if err != nil {
		return r, err
	}
	defer res.Body.Close()
	if res.StatusCode == http.StatusNotFound {
		return r, ErrNotFound
	}
	if res.StatusCode >= 300 {
		return r, readError(res)
	}
	return r, json.NewDecoder(res.Body).Decode(&r)
}

// Send submits a pacs.008 and returns the switch's pacs.002.
func (c *Client) Send(ctx context.Context, msg iso.Pacs008) (iso.Pacs002, error) {
	var st iso.Pacs002
	body, err := iso.Marshal(msg)
	if err != nil {
		return st, err
	}
	res, err := c.do(ctx, http.MethodPost, "/api/v1/iso/pacs.008", "application/xml", body)
	if err != nil {
		return st, err
	}
	defer res.Body.Close()
	if res.StatusCode >= 300 {
		return st, readError(res)
	}
	data, err := io.ReadAll(res.Body)
	if err != nil {
		return st, err
	}
	return st, iso.Unmarshal(data, &st)
}

// Status asks the switch what happened to a transfer, after a lost or failed response.
func (c *Client) Status(ctx context.Context, endToEndID string) (iso.Pacs002, error) {
	var st iso.Pacs002
	res, err := c.do(ctx, http.MethodGet, "/api/v1/iso/pacs.002/"+url.PathEscape(endToEndID), "", nil)
	if err != nil {
		return st, err
	}
	defer res.Body.Close()
	if res.StatusCode == http.StatusNotFound {
		return st, ErrNotFound
	}
	if res.StatusCode >= 300 {
		return st, readError(res)
	}
	data, err := io.ReadAll(res.Body)
	if err != nil {
		return st, err
	}
	return st, iso.Unmarshal(data, &st)
}

func (c *Client) CycleTransactions(ctx context.Context, cycle int64) ([]CycleTransaction, error) {
	res, err := c.do(ctx, http.MethodGet, fmt.Sprintf("/api/v1/settlement/cycles/%d/transactions", cycle), "", nil)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	if res.StatusCode == http.StatusNotFound {
		return nil, ErrNotFound
	}
	if res.StatusCode >= 300 {
		return nil, readError(res)
	}
	var out []CycleTransaction
	return out, json.NewDecoder(res.Body).Decode(&out)
}
