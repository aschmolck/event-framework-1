(ns eventframework.app-test
  (:use
    [eventframework [app :only [app]]
                    [commands :only [new-uuid]]
                    commands-support]
    [clojure.test :only [deftest]]
    [midje.sweet :only [facts fact contains]]
    [midje.checkers.defining :only [checker defchecker]]
    [midje.error-handling.exceptions :only [captured-throwable?]]
    [ring.mock.request :only [request body content-type]])
  (:require
   [cheshire.core :as json]
   [clj-time.core :as time]
   [clojure.java.io :as io]
   clj-time.format))

(defchecker filematch [rexp]
  (checker [file] (re-find rexp (slurp file))))

(defchecker date-roughly
  ([expected delta]
    (checker [actual]
      (and (not (captured-throwable? actual))
           (time/after? (time/plus expected delta) actual)
           (time/before? (time/minus expected delta) actual))))
  ([expected]
    (date-roughly expected (time/minutes 10))))

; FIXME: check code is 200 before parsing
(defn json-body [response]
  (json/parse-string (first (lamina.core/channel-seq (:body response))) true))

(defn command-request [type id data]
  (body (request :put (str "/ajax/command/" type "/" id))
        (json/generate-string data)))

(defn new-thread-request [command-id text]
  (command-request "newthread" command-id {:text text}))

(defn get-events-request [user position]
  (request :get (str "/ajax/events/" user "/" position)))

(defn subscribe-request [user thread]
  (command-request "subscribe" (new-uuid)
    {:user user :thread thread}))

(defn message-request [thread message]
  (command-request "message" (new-uuid)
    {:thread thread :message message}))

(deftest test-app
  (facts "serves static files"
    (app (request :get "/"))
      => (contains {:status 200
                    :body (filematch #"Proof-of-concept")})
    (app (request :get "/ajax/foo"))
      => (contains {:status 200
                    :body #"foo"})
    (app (request :get "/js/app.js"))
      => (contains {:status 200
                    :body (filematch #"readEvents")})
    (app (request :get "/does-not-exist"))
      => (contains {:status 404
                    :body (filematch #"typos")}))

  ;; FIXME this is pretty horrendous
  (with-clear-commands
	  (let [thread-command-id        (new-uuid)
	        ;; start new thread command (next position will be 1)
	        new-thread-response      (app (new-thread-request thread-command-id "Hello World!"))
	        hello-world-thread-command {:type "newthread"
	                                    :id   thread-command-id
	                                    :body {:text "Hello World!"}}
	
	        ; Look in /commands
	        commands-position-1 (app (request :get "/ajax/commands"))

	        ;; alice receives the thread started event
	        {alice-position-1 :position alice-events-from-0 :events}
	        (json-body (app (get-events-request "alice" "0")))
	
	        ;; alice subscribes (next position will be 2)
	        alice-subscribe-response (app (subscribe-request "alice" thread-command-id))
	
	        ;; alice receives subscribed event
	        {alice-position-2 :position alice-events-from-1 :events}
	        (json-body (app (get-events-request "alice" alice-position-1)))
	
	        ;; bob receives the thread started event
	        {bob-position-2 :position bob-events-from-0 :events}
	        (json-body (app (get-events-request "bob" "0")))
	
	        ;; bob subscribes (next position will be 3)
	        bob-subscribe-response (app (subscribe-request "bob" thread-command-id))
	
	        ;; bob receives subscribed event
	        {bob-position-3 :position bob-events-from-2 :events}
	        (json-body (app (get-events-request "bob" bob-position-2)))
	
	        ;; alice sends a message (next position will be 4)
	        alice-message-response (app (message-request thread-command-id "foo"))
	        test-message-event {:type "message" :body {:thread thread-command-id :message "foo"}}
	
	        ;; alice receives the message
	        {alice-position-4 :position alice-events-from-2 :events}
	        (json-body (app (get-events-request "alice" alice-position-2)))
	
	        ;; bob receives the message
	        {bob-position-4 :position bob-events-from-3 :events}
	        (json-body (app (get-events-request "bob" bob-position-3)))
	
	        ;; carol receives the thread started event
	        {carol-position-4 :position carol-events-from-0 :events}
	        (json-body (app (get-events-request "carol" "0")))
	
	        ;; carol subscribes (next position will be 5)
	        carol-subscribe-response (app (subscribe-request "carol" thread-command-id))
	
	        ;; carol receives the subscribed event and the message
	        {carol-position-5 :position carol-events-from-4 :events}
	        (json-body (app (get-events-request "carol" carol-position-4)))]
	
	       (fact "allows creation of conversation threads"
	         new-thread-response => (contains {:status 200}))
	
	       (fact "Puts the creation into the command history"
	         (first (json/parse-string (:body commands-position-1) true))
	             => (contains hello-world-thread-command))

	       (fact "includes the date in event"
	         (clj-time.format/parse
	           (clj-time.format/formatters :date-time-no-ms)
	           (:date (first alice-events-from-0))) => (date-roughly (time/now)))

	       (fact "distributes new threads to users"
	         (first alice-events-from-0) => (contains hello-world-thread-command)
	         (first bob-events-from-0)   => (contains hello-world-thread-command)
	         (first carol-events-from-0) => (contains hello-world-thread-command))
	       
	       (fact "allows subscription to threads"
	         (first alice-events-from-1) => (contains {:type "subscribe"
	                                                   :body {:thread thread-command-id :user "alice"}})
	         (first bob-events-from-2)   => (contains {:type "subscribe"
	                                                   :body {:thread thread-command-id :user "bob"}})
	         (first carol-events-from-4) => (contains {:type "subscribe"
	                                                   :body {:thread thread-command-id :user "carol"}}))
	       
	       (fact "subscribed users receive messages as they are available"
	         (first alice-events-from-2) => (contains test-message-event)
	         (first bob-events-from-3)   => (contains test-message-event))
	
	       (fact "users subscribing later receive all existing messages within the subscribed event"
	         (first (:extraevents (first carol-events-from-4))) => (contains test-message-event))
	
	       (fact "position indicators are the same across clients"
	         alice-position-2 => bob-position-2
	         alice-position-4 => bob-position-4
	         alice-position-4 => carol-position-4)))

  ;; FIXME this is pretty horrendous
  (with-clear-commands
	  (let [thread-command-id        (new-uuid)
	        ;; start new thread command (next position will be 1)
	        new-thread-response      (app (new-thread-request thread-command-id "Hello World!"))
	        hello-world-thread-command {:type "newthread"
	                                    :id   thread-command-id
	                                    :body {:text "Hello World!"}}

	        ;; Look in /commands
	        commands-position-1 (app (request :get "/ajax/commands"))

          clear-commands-response (app (body (request :put "/ajax/commands") "[]"))

          start-another-thread (app (new-thread-request (new-uuid) "Different"))

	        ;; we should see the different event after the clear
	        after-clear (json-body (app (get-events-request "alice" "0")))

          ;; now restore the commands

          restore-commands-response (app (body (request :put "/ajax/commands")
                                               (:body commands-position-1)))

          ;; and our events should be present again
	        after-restore (json-body (app (get-events-request "alice" "0")))]

         (fact "thread is different after clear"
	         (first (:events after-clear)) => (contains {:body {:text "Different"}}))

	       (fact "thread is restored after restore"
	         (first (:events after-restore)) => (contains hello-world-thread-command))))
  ;; FIXME this is pretty horrendous
  (with-clear-commands
	  (let [thread-command-id        (new-uuid)
	        ;; start new thread command (next position will be 1)
	        new-thread-response      (app (new-thread-request thread-command-id "Hello World!"))
	        hello-world-thread-command {:type "newthread"
	                                    :id   thread-command-id
	                                    :body {:text "Hello World!"}}

          ;; Now push another event
          another-thread-response (->
                                    (request :post "/ajax/commands-append")
                                    (content-type "application/json")
                                    (body (slurp (io/resource "thread-command.json")))
                                    (app))

          ; And see what we get
          after-push (json-body (app (get-events-request "alice" "0")))]

     (fact "First thread is present"
           (first (:events after-push)) => (contains {:body {:text "Hello World!"}}))

     (fact "Push is allowed"
	         another-thread-response => (contains {:status 200}))

     
     (fact "Pushed thread is present"
           (second (:events after-push)) => (contains {:body {:text "This thread was created artificially"}})))))
