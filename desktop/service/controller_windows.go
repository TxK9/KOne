//go:build windows

package main

import (
	"net"
	"sync"
	"syscall"
	"unsafe"
)

var (
	queryPerformanceCounter   = kernel32.NewProc("QueryPerformanceCounter")
	queryPerformanceFrequency = kernel32.NewProc("QueryPerformanceFrequency")
	controllerIPHelper        = syscall.NewLazyDLL("iphlpapi.dll")
	notifyControllerInterface = controllerIPHelper.NewProc("NotifyIpInterfaceChange")
	notifyControllerAddress   = controllerIPHelper.NewProc("NotifyUnicastIpAddressChange")
	cancelControllerNotify    = controllerIPHelper.NewProc("CancelMibChangeNotify2")
)

// Windows QPC includes standby and hibernation and is independent of wall-clock changes.
func serviceElapsedMillis() int64 {
	var ticks, frequency int64
	ok, _, _ := queryPerformanceCounter.Call(uintptr(unsafe.Pointer(&ticks)))
	good, _, _ := queryPerformanceFrequency.Call(uintptr(unsafe.Pointer(&frequency)))
	if ok == 0 || good == 0 || ticks < 0 || frequency <= 0 {
		return -1
	}
	return ticks/frequency*1000 + (ticks%frequency)*1000/frequency
}

type controllerInterfacePrefix struct {
	Family uint16
	_      [6]byte
	LUID   uint64
	Index  uint32
}
type controllerAddressPrefix struct {
	Address [28]byte
	_       [4]byte
	LUID    uint64
	Index   uint32
}

// Native notifications observe IPv4/IPv6 and link transitions, not a settled IPv4 snapshot.
func (s *server) watchControllerNetwork() func() {
	var mu sync.Mutex
	own := map[uint64]bool{}
	changed := func(luid uint64, index uint32) {
		mu.Lock()
		if own[luid] {
			mu.Unlock()
			return
		}
		if nic, err := net.InterfaceByIndex(int(index)); err == nil && nic.Name == "KOne" {
			own[luid] = true
			mu.Unlock()
			return
		}
		mu.Unlock()
		s.controllerNetworkEpoch.Add(1)
		go s.stopManaged(0, "controller_network_changed")
	}
	interfaceCallback := syscall.NewCallback(func(_ uintptr, row uintptr, _ uint32) uintptr {
		if row != 0 {
			value := (*controllerInterfacePrefix)(unsafe.Pointer(row))
			changed(value.LUID, value.Index)
		}
		return 0
	})
	addressCallback := syscall.NewCallback(func(_ uintptr, row uintptr, _ uint32) uintptr {
		if row != 0 {
			value := (*controllerAddressPrefix)(unsafe.Pointer(row))
			changed(value.LUID, value.Index)
		}
		return 0
	})
	var interfaceHandle, addressHandle uintptr
	first, _, _ := notifyControllerInterface.Call(0, interfaceCallback, 0, 0, uintptr(unsafe.Pointer(&interfaceHandle)))
	second, _, _ := notifyControllerAddress.Call(0, addressCallback, 0, 0, uintptr(unsafe.Pointer(&addressHandle)))
	s.controllerNetworkReady = first == 0 && second == 0 && !s.console
	return func() {
		if interfaceHandle != 0 {
			cancelControllerNotify.Call(interfaceHandle)
		}
		if addressHandle != 0 {
			cancelControllerNotify.Call(addressHandle)
		}
	}
}
