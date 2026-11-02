package main

import (
	"errors"
	"reflect"
	"testing"
	"time"
)

func TestContainedChildCannotResumeBeforeLifetimeAssignment(t *testing.T) {
	order := []string{}
	step := func(name string) func() error { return func() error { order = append(order, name); return nil } }
	if err := launchContained(step("job"), step("suspended"), step("assigned"), step("resumed"), func() { t.Fatal("successful child aborted") }); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(order, []string{"job", "suspended", "assigned", "resumed"}) {
		t.Fatalf("unsafe process order: %v", order)
	}
}
func TestContainedChildFailsClosedAtEveryContainmentBoundary(t *testing.T) {
	for failure := 0; failure < 4; failure++ {
		calls, aborts := 0, 0
		step := func() error {
			current := calls
			calls++
			if current == failure {
				return errors.New("synthetic containment failure")
			}
			return nil
		}
		if err := launchContained(step, step, step, step, func() { aborts++ }); err == nil || aborts != 1 || calls != failure+1 {
			t.Fatalf("boundary %d continued without containment", failure)
		}
	}
}

func TestStopRequiresObservedExitAndLateExitCanCorrectAnUnconfirmedReport(t *testing.T) {
	exit := newChildExit()
	reports := make(chan string, 2)
	go observeChildExit(exit, 10*time.Millisecond, func(status string) { reports <- status })
	if status := <-reports; status != "failed" {
		t.Fatal("termination request was called confirmed stop")
	}
	exit.finish(true)
	if status := <-reports; status != "stopped" {
		t.Fatal("late confirmed exit was not reported")
	}
	failed := newChildExit()
	failed.finish(false)
	observeChildExit(failed, time.Second, func(status string) {
		if status != "failed" {
			t.Fatal("failed reaping was called a confirmed stop")
		}
	})
}
