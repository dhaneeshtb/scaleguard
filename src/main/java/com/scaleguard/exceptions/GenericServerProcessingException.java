package com.scaleguard.exceptions;

public class GenericServerProcessingException extends RuntimeException{

    public GenericServerProcessingException(Exception e){
        super(e);
    }

    public GenericServerProcessingException(String message){
        super(message);
    }
}
