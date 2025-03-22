import styles from '@/styles/Home.module.css'
import React, { useEffect, useState } from 'react';
import Typewriter from 'typewriter-effect';
import axios from 'axios';
import { useAuth } from '../contexts/AuthContext';
import { useNavigate } from 'react-router-dom';
import { Input, Button } from "@chakra-ui/react";

export default function SignIn() {
  const [isLoading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const navigate = useNavigate();
  const [host, setHost] = useState<string | null>("");
  const { auth, setAuthData } = useAuth() as any;
  
    useEffect(() => {
      if (!auth.loading && auth.data) {
        navigate("/home");
      }
      console.log(auth)
    }, [auth, navigate]);

  const signIn = async (host, username, password): Promise<any> => {
    try {
      const r = await axios.post(
        host + "/signin?scaleguard=true",
        { username, password },
        {
          headers: {
            'Access-Control-Allow-Origin': '*',
          },
        }
      );
      return r;
    } catch (e: any) {
      return e.response;
    }
  };


  const handleSubmit = async (e: React.FormEvent<HTMLFormElement>): Promise<void> => {
    setLoading(true);
    e.preventDefault();
    setError("");
    setTimeout(async () => {
      const _target = e.target as any;
      const username = _target.username.value;
      const password = _target.password.value;
      const hostURL = _target.hostURL.value;
      localStorage.setItem("host", hostURL);
      const result = await signIn(hostURL, username, password);
      setLoading(false);

      if (result.status != 200) {
        setError("Invalid username or password. Retry with right credentials");
      } else {
        setLoading(false);
        const authData = { ...result.data, host: hostURL, username: username };
        window.localStorage.setItem('authData', JSON.stringify(authData));
        setAuthData({ loading: false, data: authData });
        navigate("/home");
      }
    }, 1000);
  };

  useEffect(() => {
    setHost(localStorage.getItem("host"));
  }, []);

  return (
    <main className="min-h-screen flex flex-col lg:flex-row">
      <div className="hidden lg:flex lg:w-3/5 bg-gray-900 text-white p-10 justify-center items-center">
        <div className="text-5xl font-bold">
          <Typewriter
            options={{
              strings: [
                "Connect your systems",
                "Procure certificates effortlessly",
                "API Support for dynamic host registrations",
              ],
              autoStart: true,
              loop: true,
              deleteSpeed: 50,
            }}
          />
        </div>
      </div>
      <div className="w-full lg:w-2/5 flex items-center justify-center p-6">
        <div className="shadow-lg bg-white rounded-3xl p-6 w-full max-w-md">
          <img src="/lb.svg" className="w-20 mb-4 mx-auto" alt="Logo" />
          <h2 className="text-2xl text-center text-cyan-900 font-bold">Sign in to Scaleguard</h2>
          {error && <p className="text-red-500 text-xs text-center mt-2">{error}</p>}
          <form className="mt-5" onSubmit={handleSubmit}>
            <div className="space-y-4">
              <label>
                <p className="text-left text-slate-400 pb-2">Admin Username</p>
                <Input id="username" name="username" type="text" placeholder="Enter username" />
              </label>
              <label>
                <p className="text-left text-slate-400 pb-2">Password</p>
                <Input id="password" name="password" type="password" placeholder="Enter password" />
              </label>
              <label>
                <p className="text-left text-slate-400 pb-2">Host URL</p>
                <Input defaultValue={host as any | ""} id="hostURL" name="hostURL" type="text" placeholder="Enter host URL" />
              </label>
              <Button type="submit" bg="slategray" color="white" w={"full"} isLoading={isLoading} borderRadius="full">
                Sign In
              </Button>
            </div>
          </form>
          <p className="mt-4 text-xs text-center text-gray-600">By proceeding, you agree to our <a href="#" className="underline">Terms of Use</a> and confirm you have read our <a href="#" className="underline">Privacy and Cookie Statement</a>.</p>
        </div>
      </div>
    </main>
  );
}
