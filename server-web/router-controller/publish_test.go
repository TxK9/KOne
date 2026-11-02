// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"testing"
)

const originalSample = `config global 'settings'
 option node 'shunt'
 option dns_server 'private-dns-setting-not-to-upload'
config nodes 'shunt'
 option protocol '_shunt'
 option default_node 'exit'
config nodes 'relay'
 option type 'Xray'
 option protocol 'hysteria2'
 option remarks 'Relay'
 option address 'relay.invalid'
 option port '443'
 option tls '1'
 option tls_allowInsecure '1'
 option tls_serverName 'tls.invalid'
 option hysteria2_auth_password 'selected-relay-secret'
 option hysteria2_obfs_type 'salamander'
 option hysteria2_obfs_password 'selected-obfs-secret'
 option hysteria2_hop_interval '30'
 option timeout '60'
 option group 'private-subscription-group'
config nodes 'exit'
 option type 'Xray'
 option protocol 'socks'
 option remarks 'Exit'
 option address 'exit.invalid'
 option port '1080'
 option username 'selected-user'
 option password 'selected-exit-secret'
 option chain_proxy '1'
 option preproxy_node 'relay'
 option transport 'raw'
 option tls '0'
config nodes 'unselected'
 option type 'Xray'
 option protocol 'vless'
 option address 'unselected.invalid'
 option port '443'
 option uuid 'unselected-secret-not-to-upload'
 option remarks 'Unselected'
`

func TestOriginalProjectionIncludesOnlySelectedRootsAndDependencies(t *testing.T) {
	publication, err := projectOriginal([]byte(originalSample), []string{"exit"})
	if err != nil {
		t.Fatal(err)
	}
	if publication.NodeCount != 2 || !equalStrings(publication.SelectedTags, []string{"Exit"}) {
		t.Fatal("wrong publication projection")
	}
	for _, private := range []string{"private-dns-setting", "private-subscription-group", "unselected-secret", "config global", "_shunt"} {
		if strings.Contains(publication.NativeUCI, private) {
			t.Fatal("unrelated data was projected")
		}
	}
	for _, required := range []string{"selected-relay-secret", "selected-exit-secret", "preproxy_node 'relay'", "tls_allowInsecure '1'", "hysteria2_hop_interval '30'", "timeout '60'"} {
		if !strings.Contains(publication.NativeUCI, required) {
			t.Fatal("native connection setting was changed")
		}
	}
	if _, _, err = patchPassWall([]byte(originalSample), testDevice, newRegistry(testDevice), nil); err != nil {
		t.Fatal(err)
	}
}
func TestManagedStubsUnsupportedOptionsAndBrokenChainsCannotBePublished(t *testing.T) {
	stub := Stub{NodeID: testExit, Section: sectionID(testDevice, testExit), Port: 600, Username: "kone", Password: strings.Repeat("A", 43), Name: "Managed"}
	cases := []struct {
		data  string
		roots []string
	}{
		{string(stubSection(testDevice, stub)), []string{stub.Section}},
		{strings.ReplaceAll(string(stubSection(testDevice, stub)), "kone_controller_owner", "other_owner"), []string{stub.Section}},
		{originalSample, []string{"shunt"}},
		{strings.Replace(originalSample, "address 'exit.invalid'", "address '127.0.0.1'", 1), []string{"exit"}},
		{strings.Replace(originalSample, "preproxy_node 'relay'", "preproxy_node 'missing'", 1), []string{"exit"}},
		{strings.Replace(originalSample, "transport 'raw'", "transport 'ws'", 1), []string{"exit"}},
		{strings.Replace(originalSample, "option transport 'raw'", "option tcpMptcp '1'", 1), []string{"exit"}},
		{strings.Replace(originalSample, "option transport 'raw'", "option tls_certificate_pin 'unknown-security-field'", 1), []string{"exit"}},
	}
	for index, test := range cases {
		if _, err := projectOriginal([]byte(test.data), test.roots); err == nil {
			t.Fatalf("case %d should be refused", index)
		}
	}
}
func TestPublicationDoesNotInferTLSInsecurityOrFlattenDuplicateNames(t *testing.T) {
	raw := strings.Replace(originalSample, " option tls_allowInsecure '1'\n", "", 1)
	raw = strings.Replace(raw, "option remarks 'Exit'", "option remarks 'Relay'", 1)
	projection, err := projectOriginal([]byte(raw), []string{"exit"})
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(projection.NativeUCI, "tls_allowInsecure") {
		t.Fatal("insecurity inferred")
	}
	if projection.SelectedTags[0] == "Relay" || !strings.Contains(projection.NativeUCI, "preproxy_node 'relay'") {
		t.Fatal("duplicate labels lost identity or chain")
	}
}

type fakePublisher struct {
	meta  ControllerMetadata
	posts int
}

func (p *fakePublisher) PublishingStatus(context.Context) (ControllerMetadata, error) {
	return p.meta, nil
}
func (p *fakePublisher) PublishNative(context.Context, Publication) (PublishReply, error) {
	p.posts++
	return PublishReply{OK: true, LibraryScope: "server"}, nil
}
func TestFreshOwnerPermissionPrecedesReadingLocalConfiguration(t *testing.T) {
	config := testConfig(t)
	meta := *testView().Controller
	meta.Mode = "legacy"
	meta.Required = false
	api := &fakePublisher{meta: meta}
	read := false
	if _, err := publishSelected(context.Background(), config, api, func() ([]byte, error) { read = true; return []byte(originalSample), nil }, []string{"exit"}); err == nil || read || api.posts != 0 {
		t.Fatal("unauthorized local read or upload")
	}
	api.meta.OwnerPublish = true
	if _, err := publishSelected(context.Background(), config, api, func() ([]byte, error) { read = true; return []byte(originalSample), nil }, []string{"exit"}); err != nil || !read || api.posts != 1 {
		t.Fatal("owner publishing required proxy grants")
	}
}
func TestPublicationUsesReadOnlyStatusAndExplicitServerScope(t *testing.T) {
	config := testConfig(t)
	view := testView()
	view.Controller.OwnerPublish = true
	view.Controller.Mode = "legacy"
	view.Controller.Required = false
	sequence := []string{}
	client, _ := mockClient(t, func(w http.ResponseWriter, r *http.Request) {
		sequence = append(sequence, r.URL.Path)
		switch r.URL.Path {
		case "/api/client/controller/status":
			respondJSON(w, map[string]any{"ok": true, "controller": view.Controller})
		case "/api/client/controller/nodes/publish":
			var body map[string]any
			if json.NewDecoder(r.Body).Decode(&body) != nil {
				t.Error("invalid publication")
			}
			if body["libraryScope"] != "server" {
				t.Error("scope not explicit")
			}
			if !uuidPattern.MatchString(body["requestId"].(string)) {
				t.Error("invalid publication identifier")
			}
			native := body["configText"].(string)
			if strings.Contains(native, "unselected-secret") {
				t.Error("unselected node sent")
			}
			respondJSON(w, PublishReply{OK: true, Imported: 2, Nodes: []PublishNode{{ID: testRelay, Revision: 1}, {ID: testExit, Revision: 1}}, Warnings: []string{}, AutomaticallyGranted: false, LibraryScope: "server"})
		default:
			t.Error("publisher changed capabilities or requested proxy authority")
			http.Error(w, "unexpected", 400)
		}
	})
	reply, err := publishSelected(context.Background(), config, client, func() ([]byte, error) { return []byte(originalSample), nil }, []string{"exit"})
	if err != nil {
		t.Fatal(err)
	}
	if reply.Imported != 2 || len(sequence) != 2 {
		t.Fatal("unexpected publication operation")
	}
}

// This optional test exercises the existing server converter, not a router runtime dependency.
func TestNativePublicationContractWithExistingBackend(t *testing.T) {
	backend := os.Getenv("KONE_BACKEND_CHECK")
	if backend == "" {
		t.Skip("an existing backend checkout was not supplied")
	}
	publication, err := projectOriginal([]byte(originalSample), []string{"exit"})
	if err != nil {
		t.Fatal(err)
	}
	body, _ := json.Marshal(map[string]any{"configText": publication.NativeUCI, "selectedTags": publication.SelectedTags})
	script := `import json,os,sys,tempfile,uuid
sys.path.insert(0,os.environ['KONE_BACKEND_CHECK'])
from server import ConsoleStore,parse_json_text
from fleet import Fleet
from customers import Customers
from node_controller import NodeController
from passwall_import import parse_passwall
payload=json.load(sys.stdin)
config,_,_=parse_passwall(payload['configText'])
nodes={node['tag']:node for node in config['outbounds']}
assert len(nodes)==2 and nodes[payload['selectedTags'][0]]['detour'] in nodes
assert any(node.get('tls',{}).get('insecure') is True for node in nodes.values())
with tempfile.TemporaryDirectory(prefix='kone-publish-contract-') as directory:
 store=ConsoleStore(directory);fleet=Fleet(store);customers=Customers(store,fleet)
 controller=NodeController(store,customers,parse_json_text)
 user=store.add_user({'name':'Synthetic owner','appOnly':True})['id']
 invite=customers.invite(user)
 device=customers.claim({'inviteCode':invite['inviteCode'],'deviceName':'Synthetic router'})
 controller.publishing(device['deviceId'],{'allowed':True,'expectedGeneration':0})
 result=controller.publish(device['deviceId'],{'protocol':1,'requestId':str(uuid.uuid4()),'libraryScope':'server',**payload})
 assert result['libraryScope']=='server' and result['imported']==2
 assert result['automaticallyGranted'] is False
 assert all(node['ownerUserId'] is None for node in result['nodes'])
 assert controller.listing()['devices'][0]['grantedNodeIds']==[]
print('ok')
`
	python := os.Getenv("KONE_TEST_PYTHON")
	if python == "" {
		python = "python3"
	}
	cmd := exec.Command(python, "-c", script)
	cmd.Stdin = bytes.NewReader(body)
	cmd.Env = os.Environ()
	output, err := cmd.CombinedOutput()
	if err != nil || strings.TrimSpace(string(output)) != "ok" {
		t.Fatal("synthetic native-UCI publication contract failed")
	}
}

func TestOriginalRealitySettingsAreForwardedVerbatim(t *testing.T) {
	raw := `config nodes 'vless'
 option type 'Xray'
 option protocol 'vless'
 option remarks 'Reality'
 option address 'vless.invalid'
 option port '443'
 option uuid '33333333-3333-4333-8333-333333333333'
 option transport 'raw'
 option tls '1'
 option tls_serverName 'tls.invalid'
 option flow 'xtls-rprx-vision'
 option reality '1'
 option reality_publicKey 'AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE'
 option reality_shortId '0123'
 option reality_spiderX '/synthetic-spider-path'
 option fingerprint 'chrome'
 option utls '1'
 option tcpMptcp '0'
 option happy_eyeballs '0'
 option timeout '60'
`
	publication, err := projectOriginal([]byte(raw), []string{"vless"})
	if err != nil {
		t.Fatal(err)
	}
	for _, field := range []string{"reality_publicKey 'AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE'", "reality_shortId '0123'", "reality_spiderX '/synthetic-spider-path'", "utls '1'", "timeout '60'"} {
		if !strings.Contains(publication.NativeUCI, field) {
			t.Fatal("a native Reality connection option was dropped")
		}
	}
	if strings.Contains(publication.NativeUCI, "tls_allowInsecure") {
		t.Fatal("certificate insecurity was invented")
	}
}
