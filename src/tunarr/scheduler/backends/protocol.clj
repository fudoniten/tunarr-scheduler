(ns tunarr.scheduler.backends.protocol
  "Protocol for IPTV channel backends.
  
  This protocol defines the common interface that all backend implementations
  (ErsatzTV, Tunarr, etc.) must support. It provides a backend-agnostic way to
  interact with different IPTV channel management systems.")

(defprotocol ChannelBackend
  "Protocol for IPTV channel backend operations.
  
  Implementations of this protocol provide integration with specific IPTV
  channel management systems. All methods should return data in backend-agnostic
  formats defined in the channelflow.channels namespace."

  (create-channel [backend channel-spec]
    "Create a new channel in the backend system.
    
    Args:
      backend - The backend implementation
      channel-spec - Universal channel specification map
    
    Returns:
      Map with created channel details including backend-assigned ID")

  (update-channel [backend channel-id updates]
    "Update an existing channel's configuration.
    
    Args:
      backend - The backend implementation
      channel-id - Backend-specific channel identifier
      updates - Map of fields to update
    
    Returns:
      Updated channel specification")

  (delete-channel [backend channel-id]
    "Delete a channel from the backend system.
    
    Args:
      backend - The backend implementation
      channel-id - Backend-specific channel identifier
    
    Returns:
      Map with :success boolean and optional :message")

  (get-channels [backend]
    "Retrieve all channels from the backend system.
    
    Args:
      backend - The backend implementation
    
    Returns:
      Sequence of channel specifications in universal format")

  (upload-schedule [backend channel-id schedule]
    "Upload a schedule to a channel in the backend system.
    
    Args:
      backend - The backend implementation
      channel-id - Backend-specific channel identifier
      schedule - Universal schedule specification
    
    Returns:
      Map with :success boolean and optional schedule ID")

  (get-schedule [backend channel-id]
    "Retrieve the current schedule for a channel.
    
    Args:
      backend - The backend implementation
      channel-id - Backend-specific channel identifier
    
    Returns:
      Universal schedule specification or nil if no schedule exists")

  (validate-config [backend config]
    "Validate that the backend configuration is correct.
    
    Args:
      backend - The backend implementation
      config - Configuration map for this backend
    
    Returns:
      Map with :valid? boolean and optional :errors sequence"))
