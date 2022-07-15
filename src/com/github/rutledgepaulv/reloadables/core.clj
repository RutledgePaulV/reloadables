(ns com.github.rutledgepaulv.reloadables.core)


(defprotocol Component
  (start [this]
    )
  (stop [this]
    )
  (using [this f]
    )
  (reconfigure [this state]
    ))

(def ^:dynamic *default-component-system* (atom {}))

(defn query-components
  ([selector]
   (query-components (deref *default-component-system*) selector))
  ([component-system selector]
   (get-in component-system [:components selector] #{})))

(defn register-component-factory!
  "Registers constructors and destructors for components matching a given selector."
  ([selector constructor destructor]
   (swap! *default-component-system* register-component-factory! selector constructor destructor))
  ([component-system selector constructor destructor]
   (assoc-in component-system [:factories selector] {:constructor constructor :destructor destructor})))

(defn register-configuration-lens!
  "Registers a function that accepts a map of configuration and turns it into a map of component selectors to configuration."
  ([lens-fn]
   (swap! *default-component-system* register-configuration-lens! lens-fn))
  ([component-system lens-fn]
   (update-in component-system [:lenses] (fnil conj #{}) lens-fn)))

(defn configure!
  ([configuration]
   (swap! *default-component-system* configure! configuration))
  ([component-system configuration]
   (let [lenses            (get-in component-system [:lenses])
         component-configs (->> (map (fn [x] (x configuration)) lenses) (reduce merge {}))]
     )))



(defn using*
  ([component-system selectors f]
   ))

(defmacro using [bindings & body]
  )

(defn interruptable!
  "Signal the 'usage supervisor' that they are permitted to interrupt the Thread that called interruptable!
   if a new generation becomes available for any components being used by Thread. After Thread is interrupted
   the 'usage supervisor' is responsible for restarting the process `f` with the new generations."
  []
  )

#_(defn create-component-instance [create-fn destroy-fn is-exclusive initial-configuration]
    (let [lock        (ReentrantReadWriteLock.)
          read-lock   (.readLock lock)
          write-lock  (.writeLock lock)
          constructor (delay (try (.lock write-lock) (create-fn initial-configuration) (finally (.unlock write-lock))))
          destructor  (delay (try (.lock write-lock) (destroy-fn (force constructor)) (finally (.unlock write-lock))))]
      (reify ComponentProtocol
        (start [this]
          (force constructor)
          this)
        (stop [this]
          (force destructor)
          (create-component-instance create-fn destroy-fn is-exclusive initial-configuration))
        (using [this f]
          (try (.lock read-lock) (f (force constructor)) (finally (.unlock read-lock))))
        (reconfigure [this configuration]
          (if is-exclusive
            (do (force destructor) (create-component-instance create-fn destroy-fn is-exclusive configuration))
            (do (future (force destructor)) (create-component-instance create-fn destroy-fn is-exclusive configuration)))))))


; :::components:::
; components are abstract things that can be started and stopped
; components are expected to evolve over time. Each evolution creates a new generation of the component.
; components begin their lives in a stopped state
; stopped components may be automatically started on first use
; components which fail to start successfully should be retried with periodic backoff until successful or the component is discarded in favor of a new generation
; components can be started explicitly which blocks until the component is actually started
; components can be stopped explicitly which blocks until the component is actually stopped
; components can have an "optimistic" generation strategy, which creates new generations of the components immediately and garbage collects current generations after awaiting their current users
; components can have a "pessimistic" generation strategy, which awaits users and destroys the current generation prior to creating a new generation
; components can be "reconfigured" to produce a new generation of the component
; components can be "used". while being "used" the users are guaranteed a consistent generation of the component (it will not change even if reconfigured elsewhere in the program)
; components can be "used" by processes that do not naturally halt, in which case the process will be restarted by the component with new generations as required.
; processes which do not halt must indicate that intention to the component prior to beginning their indefinite processing
; multiple components can be "used" at the same time with a guaranteed consistent view of all the components (none will change even if reconfigured elsewhere in the program)
; components may depend upon each other during their creation. depending upon another component establishes a dependency relationship
; components who depend upon another component will necessarily produce a new generation whenever the components they depend upon have new generations
; cyclical dependencies between components are strictly forbidden

; :::component factories:::
; a component factory is a function which processes configuration and produces a map of "component selector" => "component instance"
; a component selector is a map of key/value pairs
; component factories are responsible for reconfiguring components with identical selectors that already exist in the registry

; :::component registry:::
; all components created by factories are placed into a global registry grouped by all subsets of their selectors
; the component registry can be queried using any subset of their selectors by anyone wanting to use the component


(comment

  ; we define how to create database components that match some selector
  (register-component-factory!
    {:kind "database"}
    (fn constructor [config]
      )
    (fn destructor [database]
      ))

  ; we define how to create a http-connection-pool for components that match a different selector
  (register-component-factory!
    {:kind "http-conn-pool"}
    (fn constructor [config]
      (create-conn-pool config))
    (fn destructor [http-conn-pool]
      (.close http-conn-pool)))

  ; we define how to create a http-client for components that match a different selector
  (register-component-factory!
    {:kind "http-client"}
    (fn constructor [config]
      ; another component was used in a constructor! we must create a parent/child
      ; relationship between the component that was used and the component that is
      ; being created.
      (using [conn-pool {:kind "http-conn-pool" :tenant (get config :tenant)}]
             (create-http-client conn-pool)))
    (fn destructor [http-client]
      (destroy-http-client http-client)))

  ; we define what components should get created based on the configuration.
  ; this returns a mapping of "component selector" to "component configuration"
  ; the "component configuration" is passed to the "component factories" defined
  ; above in order to actually instantiate a generation of a component. If the
  ; "component config" for a given selector changes, then it causes a new generation
  ; of that pre-existing component to be created. If a new selector appears for the first
  ; time, it causes the first generation of a component to get created. If an existing selector
  ; disappears, it causes the most recent generation to be stopped / destroyed.
  (register-configuration-lens!
    (fn lens [{:keys [username password]}]
      {{:kind "database" :tenant "illuminepixels"} {:username username :password password :host "localhost"}
       {:kind "database" :tenant "amazon"}         {:username username :password password :host "localhost"}}))

  ; stage the first generation of components (but none are started yet)
  (configure! {:username "generation1" :password "****"})

  ; starts generation 1 of the {:kind "database" :tenant "amazon"} component

  (using [db {:kind "database" :tenant "amazon"}]
         (println (db/query db "select * from users")))

  ; starts generation 1 of the {:kind "database" :tenant "illuminepixels"} component

  (using [db {:kind "database" :tenant "illuminepixels"}]
         (println (db/query db "select * from users")))

  ; continues to use generation 1 of each component

  (using [az {:kind "database" :tenant "amazon"}
          ip {:kind "database" :tenant "illuminepixels"}]
         (println (db/query az "select * from users"))
         (println (db/query ip "select * from users")))

  ; creates generation 2 for each component and begins garbage collecting generation 1 as current users complete their usages
  ; any new usages will use generation 2 even if some generation 1 instances are still in use by other threads

  (configure! {:username "generation2" :password "****"})

  ; this initializes generation 2 of {:kind "database" :tenant "illuminepixels"}

  (using [db {:kind "database" :tenant "illuminepixels"}]
         (println (db/query db "select * from users")))

  ; this initializes generation 2 of {:kind "database" :tenant "amazon"}

  (using [db {:kind "database" :tenant "amazon"}]
         ; creates generation 3 for each component and begins garbage collecting generation 1 as current users complete their usages
         (configure! {:username "generation3" :password "****"})

         ; db should still refer to generation 2
         (println (db/query db "select * from users")))

  ; this initializes generation 3 of {:kind "database" :tenant "illuminepixels"}

  (using [db {:kind "database" :tenant "illuminepixels"}]
         (println (db/query db "select * from users")))

  ; this initializes a queue component

  (using [queue {:kind "queue" :tenant "illuminepixels"}]

         ; this notifies the 'usage supervisor' that this thread will never naturally relinquish its reference to the `queue` component
         ; and thereby authorizes the supervisor to interrupt this thread if a new generation of `queue` becomes available. if it
         ; is  interrupted, the `usage supervisor` is expected to launch this entire `(using ...)` form again with bindings for the new
         ; component generation
         (interruptable!)

         (loop []
           (when-some [event (async/<!! queue)]
             (println event)
             (recur))))

  )